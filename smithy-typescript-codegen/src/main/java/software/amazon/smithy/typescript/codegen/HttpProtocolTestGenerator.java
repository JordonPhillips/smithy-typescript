/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.typescript.codegen;

import static java.lang.String.format;

import java.util.Locale;
import java.util.logging.Logger;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.OperationIndex;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.protocoltests.traits.HttpMessageTestCase;
import software.amazon.smithy.protocoltests.traits.HttpRequestTestCase;
import software.amazon.smithy.protocoltests.traits.HttpRequestTestsTrait;
import software.amazon.smithy.protocoltests.traits.HttpResponseTestCase;
import software.amazon.smithy.protocoltests.traits.HttpResponseTestsTrait;
import software.amazon.smithy.utils.IoUtils;

/**
 * Generates HTTP protocol test cases to be run using Jest.
 *
 * <p>Protocol tests are defined for HTTP protocols using the
 * {@code smithy.test#httpRequestTests} and {@code smithy.test#httpResponseTests}
 * traits. When found on operations or errors attached to operations, a
 * protocol test case will be generated that asserts that the protocol
 * serialization and deserialization code creates the correct HTTP requests
 * and responses for a specific set of parameters.
 */
final class HttpProtocolTestGenerator implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(HttpProtocolTestGenerator.class.getName());
    private static final String TEST_CASE_FILE_TEMPLATE = "tests/functional/%s.spec.ts";

    private final TypeScriptSettings settings;
    private final Model model;
    private final ServiceShape service;
    private final SymbolProvider symbolProvider;
    private final Symbol serviceSymbol;

    /** Vends a TypeScript IFF it's needed. */
    private final TypeScriptDelegator delegator;

    /** The TypeScript writer that's only allocated once if needed. */
    private TypeScriptWriter writer;

    HttpProtocolTestGenerator(
            TypeScriptSettings settings,
            Model model,
            SymbolProvider symbolProvider,
            TypeScriptDelegator delegator
    ) {
        this.settings = settings;
        this.model = model;
        this.service = settings.getService(model);
        this.symbolProvider = symbolProvider;
        this.delegator = delegator;
        serviceSymbol = symbolProvider.toSymbol(service);
    }

    @Override
    public void run() {
        OperationIndex operationIndex = model.getKnowledge(OperationIndex.class);
        TopDownIndex topDownIndex = model.getKnowledge(TopDownIndex.class);

        for (OperationShape operation : topDownIndex.getContainedOperations(service)) {
            // 1. Generate test cases for each request.
            operation.getTrait(HttpRequestTestsTrait.class).ifPresent(trait -> {
                for (HttpRequestTestCase testCase : trait.getTestCases()) {
                    onlyIfProtocolMatches(testCase, () -> generateRequestTest(operation, testCase));
                }
            });
            // 2. Generate test cases for each response.
            operation.getTrait(HttpResponseTestsTrait.class).ifPresent(trait -> {
                for (HttpResponseTestCase testCase : trait.getTestCases()) {
                    onlyIfProtocolMatches(testCase, () -> generateResponseTest(operation, testCase));
                }
            });
            // 3. Generate test cases for each error.
            for (StructureShape error : operationIndex.getErrors(operation)) {
                error.getTrait(HttpResponseTestsTrait.class).ifPresent(trait -> {
                    for (HttpResponseTestCase testCase : trait.getTestCases()) {
                        onlyIfProtocolMatches(testCase, () -> {
                            generateErrorResponseTest(operation, error, testCase);
                        });
                    }
                });
            }
        }
    }

    // Only generate test cases when its protocol matches the target protocol.
    private <T extends HttpMessageTestCase> void onlyIfProtocolMatches(T testCase, Runnable runnable) {
        if (testCase.getProtocol().equals(settings.getProtocol())) {
            LOGGER.fine(() -> format("Generating protocol test case for %s.%s", service.getId(), testCase.getId()));
            allocateWriterIfNeeded();
            runnable.run();
        }
    }

    private void allocateWriterIfNeeded() {
        if (writer == null) {
            delegator.useFileWriter(createTestCaseFilename(), writer -> {
                this.writer = writer;
            });
            writer.addDependency(TypeScriptDependency.AWS_SDK_TYPES);
            writer.addDependency(TypeScriptDependency.AWS_SDK_PROTOCOL_HTTP);
            // Add the template to each generated test.
            writer.write(IoUtils.readUtf8Resource(getClass(), "protocol-test-stub.ts"));
        }
    }

    private String createTestCaseFilename() {
        String baseName = settings.getProtocol().toLowerCase(Locale.ENGLISH)
                .replace("-", "_")
                .replace(".", "_");
        return TEST_CASE_FILE_TEMPLATE.replace("%s", baseName);
    }

    private void generateRequestTest(OperationShape operation, HttpRequestTestCase testCase) {
        Symbol operationSymbol = symbolProvider.toSymbol(operation);

        testCase.getDocumentation().ifPresent(writer::writeDocs);
        writer.openBlock("it($S, async () => {", "}\n", testCase.getId(), () -> {
            // Create a client with a custom request handler that intercepts requests.
            writer.openBlock("const client = new $T({", "});\n", serviceSymbol, () -> {
                writer.write("requestHandler: new RequestSerializationTestHandler()");
            });

            // Set tht command's parameters from the test case.
            String jsonParameters = Node.prettyPrintJson(testCase.getParams());
            writer.write("const command = new $T($L);\n", operationSymbol, jsonParameters);

            // Send the request and look for the expected exception to then perform assertions.
            // TODO: try/catch and if/else are still cumbersome with TypeScriptWriter.
            writer.write("try {\n"
                         + "  await client.send(command);\n"
                         + "  throw 'Expected an EXPECTED_REQUEST_SERIALIZATION_ERROR to be thrown';\n"
                         + "} catch (err) {\n"
                         + "  if (!(err instanceof EXPECTED_REQUEST_SERIALIZATION_ERROR)) {\n"
                         + "    throw err;\n"
                         + "  }\n"
                         + "  const r = err.request;")
                    .indent()
                    .call(() -> writeRequestAssertions(operation, testCase))
                    .dedent()
                    .write("}");
        });
    }

    // Ensure that the serialized request matches the expected request.
    private void writeRequestAssertions(OperationShape operation, HttpRequestTestCase testCase) {
        writer.openBlock("if (r.method !== $S) {", "}\n", testCase.getMethod(), () -> {
            writer.write("throw `Expected request method to equal $S, but found $${r.method}`;", testCase.getMethod());
        });

        writer.openBlock("if (r.path !== $S) {", "}\n", testCase.getUri(), () -> {
            writer.write("throw `Expected request path to equal $S, but found $${r.method}`;", testCase.getUri());
        });

        // TODO: Add remaining request test assertions like headers, query parameters, etc.
    }

    private void generateResponseTest(OperationShape operation, HttpResponseTestCase testCase) {
        testCase.getDocumentation().ifPresent(writer::writeDocs);
        writer.openBlock("it($S, async () => {", "}\n", testCase.getId(), () -> {
            // TODO: implement response test generation. This requires us to
            //   determine how to deserialize responses without sending requests.
        });
    }

    private void generateErrorResponseTest(
            OperationShape operation,
            StructureShape error,
            HttpResponseTestCase testCase
    ) {
        testCase.getDocumentation().ifPresent(writer::writeDocs);
        writer.openBlock("it($S, async () => {", "}\n", testCase.getId(), () -> {
            // TODO: implement response test generation that ensures we can
            //   properly detect and deserialize errors.
        });
    }

    // Ensure that the serialized response matches the expected response.
    private void writeResponseAssertions(OperationShape operation, HttpResponseTestCase testCase) {
        // TODO: implement response test assertions similar to request test assertions.
    }
}

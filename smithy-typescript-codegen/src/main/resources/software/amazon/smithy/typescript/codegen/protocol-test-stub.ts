import { HttpHandlerOptions } from "@aws-sdk/types";
import { HttpHandler, HttpRequest, HttpResponse } from "@aws-sdk/protocol-http";

/**
 * Throws an expected exception that contains the serialized request.
 */
class EXPECTED_REQUEST_SERIALIZATION_ERROR {
    constructor(readonly request: HttpRequest) {}
}

/**
 * Throws an EXPECTED_REQUEST_SERIALIZATION_ERROR error before sending a
 * request. The thrown exception contains the serialized request.
 */
export class RequestSerializationTestHandler implements HttpHandler {
    handle(
        request: HttpRequest,
        options: HttpHandlerOptions
    ): Promise<{ response: HttpResponse }> {
        return Promise.reject(new EXPECTED_REQUEST_SERIALIZATION_ERROR(request));
    }
}

/**
 * Throws an EXPECTED_RESPONSE_DESERIALIZATION_ERROR error after parsing
 * a response. The sending of a request is intercepted, and the thrown exception
 * contains the serialized request.
 */
export class ResponseDeserializationTestHandler implements HttpHandler {
    handle(
        request: HttpRequest,
        options: HttpHandlerOptions
    ): Promise<{ response: HttpResponse }> {
        return Promise.reject(new EXPECTED_REQUEST_SERIALIZATION_ERROR(request));
    }
}

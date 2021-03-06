package pl.touk.mockserver.server

import com.sun.net.httpserver.HttpExchange
import groovy.transform.PackageScope
import groovy.util.logging.Slf4j
import pl.touk.mockserver.api.common.Method

import java.util.concurrent.CopyOnWriteArrayList

@Slf4j
@PackageScope
class ContextExecutor {
    private final HttpServerWraper httpServerWraper
    final String path
    private final List<Mock> mocks

    ContextExecutor(HttpServerWraper httpServerWraper, Mock initialMock) {
        this.httpServerWraper = httpServerWraper
        this.path = "/${initialMock.path}"
        this.mocks = new CopyOnWriteArrayList<>([initialMock])
        httpServerWraper.createContext(path) {
            HttpExchange ex ->
                try {
                    applyMocks(ex)
                } catch (Exception e) {
                    log.error("Exceptiony occured handling request", e)
                    throw e
                } finally {
                    ex.close()
                }
        }
    }

    private void applyMocks(HttpExchange ex) {
        MockRequest request = new MockRequest(ex.requestBody.text, ex.requestHeaders, ex.requestURI)
        log.info('Mock received input')
        log.debug("Request: ${request.text}")
        for (Mock mock : mocks) {
            try {
                if (mock.match(Method.valueOf(ex.requestMethod), request)) {
                    log.debug("Mock ${mock.name} match request ${request.text}")
                    MockResponse httpResponse = mock.apply(request)
                    fillExchange(ex, httpResponse)
                    log.trace("Mock ${mock.name} response with body ${httpResponse.text}")
                    return
                }
                log.debug("Mock ${mock.name} does not match request")
            } catch (Exception e) {
                log.warn("An exception occured when matching or applying mock ${mock.name}", e)
            }
        }
        log.warn("Any mock does not match request ${request.text}")
        Util.createResponse(ex, request.text, 404)
    }

    String getPath() {
        return path.substring(1)
    }

    String getContextPath() {
        return path
    }

    private static void fillExchange(HttpExchange httpExchange, MockResponse response) {
        response.headers.each {
            httpExchange.responseHeaders.add(it.key, it.value)
        }
        Util.createResponse(httpExchange, response.text, response.statusCode)
    }

    List<MockEvent> removeMock(String name) {
        Mock mock = mocks.find { it.name == name }
        if (mock) {
            mocks.remove(mock)
            return mock.history
        }
        return []
    }

    List<MockEvent> peekMock(String name) {
        Mock mock = mocks.find { it.name == name }
        if (mock) {
            return mock.history
        }
        return []
    }

    void addMock(Mock mock) {
        mocks << mock
    }

    List<Mock> getMocks() {
        return mocks
    }
}

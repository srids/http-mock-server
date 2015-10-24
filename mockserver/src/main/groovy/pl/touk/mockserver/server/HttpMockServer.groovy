package pl.touk.mockserver.server

import com.sun.net.httpserver.HttpExchange
import groovy.util.logging.Slf4j
import pl.touk.mockserver.api.request.AddMock
import pl.touk.mockserver.api.request.MockServerRequest
import pl.touk.mockserver.api.request.PeekMock
import pl.touk.mockserver.api.request.RemoveMock
import pl.touk.mockserver.api.response.*

import javax.xml.bind.JAXBContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

import static pl.touk.mockserver.server.Util.createResponse

@Slf4j
class HttpMockServer {

    private final HttpServerWraper httpServerWraper
    private final Map<Integer, HttpServerWraper> childServers = new ConcurrentHashMap<>()
    private final Set<String> mockNames = new CopyOnWriteArraySet<>()

    private static
    final JAXBContext requestJaxbContext = JAXBContext.newInstance(AddMock.package.name, AddMock.classLoader)

    HttpMockServer(int port = 9999) {
        httpServerWraper = new HttpServerWraper(port)

        httpServerWraper.createContext('/serverControl', {
            HttpExchange ex ->
                try {
                    if (ex.requestMethod == 'GET') {
                        listMocks(ex)
                    } else if (ex.requestMethod == 'POST') {
                        MockServerRequest request = requestJaxbContext.createUnmarshaller().unmarshal(ex.requestBody) as MockServerRequest
                        if (request instanceof AddMock) {
                            addMock(request, ex)
                        } else if (request instanceof RemoveMock) {
                            removeMock(request, ex)
                        } else if (request instanceof PeekMock) {
                            peekMock(request, ex)
                        } else {
                            throw new RuntimeException('Unknown request')
                        }
                    } else {
                        throw new RuntimeException('Unknown request')
                    }
                } catch (Exception e) {
                    createErrorResponse(ex, e)
                }
        })
    }

    void listMocks(HttpExchange ex) {
        Mocks mockListing = new Mocks(
                mocks: listMocks().collect {
                    new MockReport(
                            name: it.name,
                            path: it.path,
                            port: it.port,
                            predicate: it.predicateClosureText,
                            response: it.responseClosureText,
                            responseHeaders: it.responseHeadersClosureText,
                            soap: it.soap,
                            method: it.method,
                            statusCode: it.statusCode as int,
                            schema: it.schema
                    )
                }
        )
        createResponse(ex, mockListing, 200)
    }

    Set<Mock> listMocks() {
        return childServers.values().collect { it.mocks }.flatten() as TreeSet<Mock>
    }

    private void addMock(AddMock request, HttpExchange ex) {
        String name = request.name
        if (name in mockNames) {
            throw new RuntimeException('mock already registered')
        }
        Mock mock = mockFromRequest(request)
        HttpServerWraper child = getOrCreateChildServer(mock.port)
        child.addMock(mock)
        mockNames << name
        createResponse(ex, new MockAdded(), 200)
    }

    private static Mock mockFromRequest(AddMock request) {
        Mock mock = new Mock(request.name, request.path, request.port)
        mock.predicate = request.predicate
        mock.response = request.response
        mock.soap = request.soap
        mock.statusCode = request.statusCode
        mock.method = request.method
        mock.responseHeaders = request.responseHeaders
        mock.schema = request.schema
        return mock
    }

    private HttpServerWraper getOrCreateChildServer(int mockPort) {
        HttpServerWraper child = childServers[mockPort]
        if (!child) {
            child = new HttpServerWraper(mockPort)
            childServers.put(mockPort, child)
        }
        return child
    }

    private void removeMock(RemoveMock request, HttpExchange ex) {
        String name = request.name
        boolean skipReport = request.skipReport ?: false
        if (!(name in mockNames)) {
            throw new RuntimeException('mock not registered')
        }
        log.info("Removing mock $name")
        List<MockEvent> mockEvents = skipReport ? [] : childServers.values().collect {
            it.removeMock(name)
        }.flatten() as List<MockEvent>
        mockNames.remove(name)
        MockRemoved mockRemoved = new MockRemoved(
                mockEvents: createMockEventReports(mockEvents)
        )
        createResponse(ex, mockRemoved, 200)
    }

    private static List<MockEventReport> createMockEventReports(List<MockEvent> mockEvents) {
        return mockEvents.collect {
            new MockEventReport(
                    request: new MockRequestReport(
                            text: it.request.text,
                            headers: new MockRequestReport.Headers(headers: it.request.headers.collect {
                                new Parameter(name: it.key, value: it.value)
                            }),
                            queryParams: new MockRequestReport.QueryParams(queryParams: it.request.query.collect {
                                new Parameter(name: it.key, value: it.value)
                            }),
                            path: new MockRequestReport.Path(pathParts: it.request.path)
                    ),
                    response: new MockResponseReport(
                            statusCode: it.response.statusCode,
                            text: it.response.text,
                            headers: new MockResponseReport.Headers(headers: it.response.headers.collect {
                                new Parameter(name: it.key, value: it.value)
                            })
                    )
            )
        }
    }

    private void peekMock(PeekMock request, HttpExchange ex) {
        String name = request.name
        if (!(name in mockNames)) {
            throw new RuntimeException('mock not registered')
        }
        log.trace("Peeking mock $name")
        List<MockEvent> mockEvents = childServers.values().collect { it.peekMock(name) }.flatten() as List<MockEvent>
        MockPeeked mockPeeked = new MockPeeked(
                mockEvents: createMockEventReports(mockEvents)
        )
        createResponse(ex, mockPeeked, 200)
    }

    private static void createErrorResponse(HttpExchange ex, Exception e) {
        log.warn('Exception occured', e)
        createResponse(ex, new ExceptionOccured(value: e.message), 400)
    }

    void stop() {
        childServers.values().each { it.stop() }
        httpServerWraper.stop()
    }
}

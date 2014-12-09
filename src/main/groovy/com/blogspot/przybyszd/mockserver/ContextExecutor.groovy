package com.blogspot.przybyszd.mockserver

import com.sun.net.httpserver.HttpExchange
import groovy.util.slurpersupport.GPathResult

import java.util.concurrent.CopyOnWriteArrayList

class ContextExecutor {
    private final HttpServerWraper httpServerWraper
    final String path
    private final List<Mock> mocks

    ContextExecutor(HttpServerWraper httpServerWraper, String path, Mock initialMock) {
        this.httpServerWraper = httpServerWraper
        this.path = path
        this.mocks = new CopyOnWriteArrayList<>([initialMock])
        httpServerWraper.createContext(path,{
            HttpExchange ex ->
                ex.sendResponseHeaders(200, 0)
                String input = ex.requestBody.text
                println "Mock received input"
                GPathResult inputXml = new XmlSlurper().parseText(input)
                for (Mock mock : mocks){
                    GPathResult xml = inputXml
                    try {
                        if (mock.soap) {
                            if(xml.name() == 'Envelope' && xml.Body.size() > 0){
                                xml = getSoapBodyContent(xml)
                            }else{
                                continue
                            }
                        }
                        if (xml != null && mock.predicate(xml)) {
                            println "Mock ${mock.name} invoked"
                            ++mock.counter
                            String response = mock.responseOk(xml)
                            ex.responseBody << (mock.soap ? wrapSoap(response) : response)
                            ex.responseBody.close()
                            return
                        }
                    }catch (Exception e){
                        e.printStackTrace()
                    }
                }
                ex.responseBody << "<invalidInput/>"
                ex.responseBody.close()
        })
    }

    private static GPathResult getSoapBodyContent(GPathResult xml) {
        return xml.Body.'**'[1]
    }

    int removeMock(String name) {
        Mock mock = mocks.find {it.name == name}
        if(mock){
            mocks.remove(mock)
        }
        return mock.counter
    }

    void addMock(Mock mock){
        mocks << mock
    }

    private static String wrapSoap(String request) {
        """<?xml version='1.0' encoding='UTF-8'?>
            <soap-env:Envelope xmlns:soap-env='http://schemas.xmlsoap.org/soap/envelope/' xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/08/addressing">
                <soap-env:Body>${request}</soap-env:Body>
            </soap-env:Envelope>"""
    }
}
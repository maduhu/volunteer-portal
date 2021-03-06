package au.org.ala.volunteer

import grails.events.Listener

import javax.annotation.PostConstruct
import javax.annotation.PreDestroy

class FrontPageService {

    public static final String ALERT_MESSAGE = 'alertMessage'

    def eventSourceService

    def eventSourceStartMessage

    @PostConstruct
    void init() {
        eventSourceStartMessage = eventSourceService.addEventSourceStartMessage { userId ->
            log.debug("Getting Front Page System Message")
            def systemMessage = FrontPage.first().systemMessage
            log.debug("Got Front Page System Message")
            [createMessage(systemMessage)]
        }
    }

    @PreDestroy
    void destroy() {
        eventSourceService.removeEventSourceStartMessage(eventSourceStartMessage)
    }

    private static EventSourceMessage createMessage(String message) {
        new EventSourceMessage(event: ALERT_MESSAGE, data: message)
    }

    @Listener(topic=FrontPageService.ALERT_MESSAGE)
    void alertMessage(String alert) {
        try {
            log.debug("On Alert Message")
            eventSourceService.sendToEveryone(createMessage(alert))
        } catch (e) {
            log.error("Exception caught while handling system message change", e)
        }
    }
}

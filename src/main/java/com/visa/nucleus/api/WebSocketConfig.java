package com.visa.nucleus.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Configures a STOMP-over-WebSocket endpoint so the Next.js dashboard
 * can subscribe to real-time session-status updates.
 *
 * <p>Clients connect to {@code /ws/sessions} and subscribe to
 * {@code /topic/sessions} to receive broadcast messages whenever an
 * {@link com.visa.nucleus.core.AgentSession} changes.</p>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/sessions")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }
}

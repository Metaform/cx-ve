package com.metaform.dxonboarding.adapter.in.web;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.deser.DeserializationProblemHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unknown properties in a request body are ignored, as the TSP ignores them — but noted, so the
 * controller can warn about them: a caller sending fields nobody reads is worth knowing about.
 * Each one is recorded on the current request, by its path (e.g. {@code legalEntity.foo}).
 */
@Component
public class UnknownProperties implements JsonMapperBuilderCustomizer {

    private static final String ATTRIBUTE = UnknownProperties.class.getName();

    @Override
    public void customize(JsonMapper.Builder builder) {
        builder.addHandler(new Recorder());
    }

    /** The unknown properties of the current request's body, in the order read; empty outside a request. */
    @SuppressWarnings("unchecked")
    static List<String> ofCurrentRequest() {
        var attributes = RequestContextHolder.getRequestAttributes();
        var recorded = attributes == null ? null : attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return recorded == null ? List.of() : List.copyOf((List<String>) recorded);
    }

    static class Recorder extends DeserializationProblemHandler {

        @Override
        @SuppressWarnings("unchecked")
        public boolean handleUnknownProperty(DeserializationContext context, JsonParser parser,
                                             ValueDeserializer<?> deserializer, Object beanOrClass,
                                             String propertyName) {
            var attributes = RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                var recorded = (List<String>) attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
                if (recorded == null) {
                    recorded = new ArrayList<>();
                    attributes.setAttribute(ATTRIBUTE, recorded, RequestAttributes.SCOPE_REQUEST);
                }
                recorded.add(path(pointer(context, parser, propertyName)));
            }
            parser.skipChildren();
            return true;
        }

        /**
         * Where the property is in the body. A record's unknown properties are buffered and handed
         * over once the record is read, on a parser of their own: the record's place is then where
         * the body's parser stands.
         */
        private static String pointer(DeserializationContext context, JsonParser parser, String propertyName) {
            var body = context.getParser();
            return parser == body
                    ? parser.streamReadContext().pathAsPointer().toString()
                    : body.streamReadContext().pathAsPointer() + "/" + propertyName;
        }

        /** A JSON pointer as a property path: {@code /ucas/0/foo} is {@code ucas[0].foo}. */
        static String path(String pointer) {
            var path = new StringBuilder();
            for (var segment : pointer.substring(pointer.startsWith("/") ? 1 : 0).split("/")) {
                var name = segment.replace("~1", "/").replace("~0", "~");
                if (name.matches("\\d+")) {
                    path.append('[').append(name).append(']');
                } else {
                    path.append(path.isEmpty() ? "" : ".").append(name);
                }
            }
            return path.toString();
        }
    }
}

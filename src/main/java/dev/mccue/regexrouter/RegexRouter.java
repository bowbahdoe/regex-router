package dev.mccue.regexrouter;

import dev.mccue.rosie.IntoResponse;
import dev.mccue.rosie.Request;
import org.jspecify.annotations.Nullable;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Implementation of a Router that just does a linear scan through regexes.
 *
 * <p>
 * This was chosen for my demo since it is the easiest thing to implement, not because it
 * is the most performant.
 * </p>
 *
 * <p>
 * That being said, this seems to be roughly how Django handles routing.
 * </p>
 *
 * <p>
 * From <a href="https://docs.djangoproject.com/en/3.2/topics/http/urls/">the django docs</a>
 * </p>
 *
 * <ul>
 *     <li>Django runs through each URL pattern, in order, and stops at the first one that matches the requested URL, matching against path_info.</li>
 *     <li>Once one of the URL patterns matches, Django imports and calls the given view, which is a Python function (or a class-based view). The view gets passed the following arguments:</li>
 *     <li>
 *         <ul>
 *            <li>An instance of HttpRequest.</li>
 *            <li>If the matched URL pattern contained no named groups, then the matches from the regular expression are provided as positional arguments.</li>
 *            <li>The keyword arguments are made up of any named parts matched by the path expression that are provided, overridden by any arguments specified in the optional kwargs argument to django.urls.path() or django.urls.re_path().</li>
 *        </ul>
 *     </li>
 * </ul>
 *
 * <p>
 *     For the syntax of declaring a named group, <a href="https://stackoverflow.com/questions/415580/regex-named-groups-in-java">this stack overflow question should be useful.</a>
 * </p>
 *
 * @param <Ctx> The context that will be available for requests down the chain.
 */
public final class RegexRouter<Ctx extends @Nullable Object> {
    private final List<Mapping<Ctx>> mappings;

    private RegexRouter(Builder<Ctx> builder) {
        final var mappings = builder.mappings;
        this.mappings = new ArrayList<>();
        for (final var mapping : mappings) {
            for (final var method : mapping.methods) {
                this.mappings.add(new Mapping<>(method.toLowerCase(), mapping.routePattern(), mapping.handler()));
            }
        }
    }

    /**
     * Creates a {@link Builder}.
     *
     * @return A builder.
     * @param <Ctx> The context for the router.
     */
    public static <Ctx extends @Nullable Object> Builder<Ctx> builder() {
        return new Builder<>();
    }

    /**
     * A simple handler that takes a request and produces a response.
     */
    public interface Handler {
        /**
         * Handles the request.
         * @param request The request.
         * @return Something which can be turned into a {@link dev.mccue.rosie.Response}
         */
        IntoResponse handle(Request request);
    }

    /**
     * Handler that takes a request and some additional context and produces a response.
     * @param <Ctx> The additional context
     */
    public interface HandlerTakingContext<Ctx extends @Nullable Object> {
        /**
         * Handles the request.
         * @param context Context to pass along..
         * @param request The request.
         * @return Something which can be turned into a {@link dev.mccue.rosie.Response}
         */
        IntoResponse handle(Ctx context, Request request);
    }

    /**
     * Handler that takes a request, some additional context, and any route params and produces a response.
     * @param <Ctx> The additional context
     */
    public interface HandlerTakingContextAndRouteParams<Ctx extends @Nullable Object> {
        /**
         * Handles the request.
         * @param context Context to pass along.
         * @param routeParams Route params parsed from the request.
         * @param request The request.
         * @return Something which can be turned into a {@link dev.mccue.rosie.Response}
         */
        IntoResponse handle(Ctx context, RouteParams routeParams, Request request);
    }

    /**
     * Takes a matcher and provides an implementation of RouteParams on top of it.
     *
     * <p>
     *     Makes the assumption that it takes ownership of the matcher and will be exposed only via
     *     the interface, so the mutability of the matcher is not relevant.
     * </p>
     */
    record MatcherRouteParams(Matcher matcher) implements RouteParams {
        @Override
        public Optional<String> positionalParameter(int pos) {
            if (matcher.groupCount() > pos - 1 || pos < 0) {
                return Optional.empty();
            }
            else {
                return Optional.of(URLDecoder.decode(matcher.group(pos + 1), StandardCharsets.UTF_8));
            }
        }

        @Override
        public Optional<String> namedParameter(String name) {
            try {
                final var namedGroup = matcher.group(name);
                if (namedGroup == null) {
                    return Optional.empty();
                }
                else {
                    return Optional.of(URLDecoder.decode(namedGroup, StandardCharsets.UTF_8));
                }
            } catch (IllegalArgumentException ex) {
                // Yes this is bad, but there is no interface that a matcher gives
                // for verifying whether a named group even exists in the pattern.
                // If no match exists it will throw this exception, so for better or worse
                // this should be okay. JDK never changes.
                return Optional.empty();
            }
        }
    }

    /**
     * Handles the request if there is a matching handler.
     * @param ctx The context to pass through.
     * @param request The request to handle.
     * @return A response, if a matching handler was found.
     */
    public Optional<IntoResponse> handle(Ctx ctx, Request request) {
        for (final var mapping : this.mappings) {
            final var method = request.requestMethod();
            final var pattern = mapping.routePattern();
            final var matcher = pattern.matcher(request.uri());
            if (method.equalsIgnoreCase(mapping.method) && matcher.matches()) {
                return Optional.of(mapping.handler().handle(
                        ctx,
                        new MatcherRouteParams(matcher),
                        request
                ));
            }
        }
        return Optional.empty();
    }

    private record Mapping<Ctx extends @Nullable Object>(
            String method,
            Pattern routePattern,
            HandlerTakingContextAndRouteParams<Ctx> handler
    ) {}
    private record MappingWithMethods<Ctx extends @Nullable Object>(
            Set<String> methods,
            Pattern routePattern,
            HandlerTakingContextAndRouteParams<Ctx> handler
    ) {}

    /**
     * A builder for {@link RegexRouter}.
     * @param <Ctx> The context that will be available to requests.
     */
    public static final class Builder<Ctx extends @Nullable Object> {
        private final List<MappingWithMethods<Ctx>> mappings;

        private Builder() {
            this.mappings = new ArrayList<>();
        }

        /**
         * Adds a mapping to the list of handlers.
         * @param method The HTTP method to handle.
         * @param routePattern The regex to match.
         * @param handler The handler to use.
         * @return The updated {@link Builder}
         */
        public Builder<Ctx> addMapping(
                String method,
                Pattern routePattern,
                Handler handler
        ) {
            this.addMapping(Set.of(method), routePattern, handler);
            return this;
        }

        /**
         * Adds a mapping to the list of handlers.
         * @param methods The HTTP methods to handle.
         * @param routePattern The regex to match.
         * @param handler The handler to use.
         * @return The updated {@link Builder}
         */
        public Builder<Ctx> addMapping(
                Set<String> methods,
                Pattern routePattern,
                Handler handler
        ) {
            this.addMapping(methods, routePattern, (ctx, routeParams, request) -> handler.handle(request));
            return this;
        }

        /**
         * Adds a mapping to the list of handlers.
         * @param method The HTTP method to handle.
         * @param routePattern The regex to match.
         * @param handler The handler to use.
         * @return The updated {@link Builder}
         */
        public Builder<Ctx> addMapping(
                String method,
                Pattern routePattern,
                HandlerTakingContext<? super Ctx> handler
        ) {
            this.addMapping(Set.of(method), routePattern, handler);
            return this;
        }

        /**
         * Adds a mapping to the list of handlers.
         * @param methods The HTTP methods to handle.
         * @param routePattern The regex to match.
         * @param handler The handler to use.
         * @return The updated {@link Builder}
         */
        public Builder<Ctx> addMapping(
                Set<String> methods,
                Pattern routePattern,
                HandlerTakingContext<? super Ctx> handler
        ) {
            this.addMapping(methods, routePattern, (ctx, routeParams, request) -> handler.handle(ctx, request));
            return this;
        }

        /**
         * Adds a mapping to the list of handlers.
         * @param method The HTTP method to handle.
         * @param routePattern The regex to match.
         * @param handler The handler to use.
         * @return The updated {@link Builder}
         */
        public Builder<Ctx> addMapping(
                String method,
                Pattern routePattern,
                HandlerTakingContextAndRouteParams<? super Ctx> handler
        ) {
            this.addMapping(Set.of(method), routePattern, handler);
            return this;
        }

        /**
         * Adds a mapping to the list of handlers.
         * @param methods The HTTP methods to handle.
         * @param routePattern The regex to match.
         * @param handler The handler to use.
         * @return The updated {@link Builder}
         */
        public Builder<Ctx> addMapping(
                Set<String> methods,
                Pattern routePattern,
                HandlerTakingContextAndRouteParams<? super Ctx> handler
        ) {
            Objects.requireNonNull(methods);
            Objects.requireNonNull(routePattern);
            Objects.requireNonNull(handler);

            if (!methods.isEmpty()) {
                this.mappings.add(new MappingWithMethods<>(
                        methods.stream()
                                .map(String::toLowerCase)
                                .collect(Collectors.toUnmodifiableSet()),
                        routePattern,
                        handler::handle
                ));
            }
            return this;
        }

        /**
         * Builds the {@link RegexRouter}.
         * @return The built router, ready to handle requests.
         */
        public RegexRouter<Ctx> build() {
            return new RegexRouter<>(this);
        }
    }
}

package dev.mccue.regexrouter;

import dev.mccue.rosie.IntoResponse;
import dev.mccue.rosie.Request;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Implementation of a Router that just does a linear scan through regexes.
 *
 * This was chosen for my demo since it is the easiest thing to implement, not because it
 * is the most performant.
 *
 * That being said, this seems to be roughly how Django handles routing.
 *
 * From @see <a href="https://docs.djangoproject.com/en/3.2/topics/http/urls/"></a>
 *
 * - Django runs through each URL pattern, in order, and stops at the first one that matches the requested URL, matching against path_info.
 * - Once one of the URL patterns matches, Django imports and calls the given view, which is a Python function (or a class-based view). The view gets passed the following arguments:
 *   - An instance of HttpRequest.
 *   - If the matched URL pattern contained no named groups, then the matches from the regular expression are provided as positional arguments.
 *   - The keyword arguments are made up of any named parts matched by the path expression that are provided, overridden by any arguments specified in the optional kwargs argument to django.urls.path() or django.urls.re_path().
 *
 * https://stackoverflow.com/questions/415580/regex-named-groups-in-java
 */
public final class RegexRouter<Ctx> {
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

    public static <Ctx> Builder<Ctx> builder() {
        return new Builder<>();
    }

    public interface Handler {
        IntoResponse handle(Request request);
    }

    public interface HandlerTakingContext<Ctx> {
        IntoResponse handle(Ctx context, Request request);
    }

    public interface HandlerTakingContextAndRouteParams<Ctx> {
        IntoResponse handle(Ctx context, RouteParams routeParams, Request request);
    }

    /**
     * Takes a matcher and provides an implementation of RouteParams on top of it.
     *
     * Makes the assumption that it takes ownership of the matcher and will be exposed only via
     * the interface, so the mutability of the matcher is not relevant.
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

    private record Mapping<Ctx>(
            String method,
            Pattern routePattern,
            HandlerTakingContextAndRouteParams<Ctx> handler
    ) {}
    private record MappingWithMethods<Ctx>(
            Set<String> methods,
            Pattern routePattern,
            HandlerTakingContextAndRouteParams<Ctx> handler
    ) {}

    public static final class Builder<Ctx> {
        private final List<MappingWithMethods<Ctx>> mappings;

        private Builder() {
            this.mappings = new ArrayList<>();
        }

        public Builder<Ctx> addMapping(
                String method,
                Pattern routePattern,
                Handler handler
        ) {
            this.addMapping(Set.of(method), routePattern, handler);
            return this;
        }

        public Builder<Ctx> addMapping(
                Set<String> methods,
                Pattern routePattern,
                Handler handler
        ) {
            this.addMapping(methods, routePattern, (ctx, routeParams, request) -> handler.handle(request));
            return this;
        }

        public Builder<Ctx> addMapping(
                String method,
                Pattern routePattern,
                HandlerTakingContext<? super Ctx> handler
        ) {
            this.addMapping(Set.of(method), routePattern, handler);
            return this;
        }

        public Builder<Ctx> addMapping(
                Set<String> methods,
                Pattern routePattern,
                HandlerTakingContext<? super Ctx> handler
        ) {
            this.addMapping(methods, routePattern, (ctx, routeParams, request) -> handler.handle(ctx, request));
            return this;
        }

        public Builder<Ctx> addMapping(
                String method,
                Pattern routePattern,
                HandlerTakingContextAndRouteParams<? super Ctx> handler
        ) {
            this.addMapping(Set.of(method), routePattern, handler);
            return this;
        }

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

        public RegexRouter<Ctx> build() {
            return new RegexRouter<>(this);
        }
    }
}

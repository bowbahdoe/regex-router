package dev.mccue.regexrouter;

import dev.mccue.rosie.Handler;
import dev.mccue.rosie.IntoResponse;
import dev.mccue.rosie.Request;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.jetty.http.HttpMethod;

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
public final class RegexRouter<Ctx> implements RequestRouter {
    private final EnumMap<HttpMethod, List<Mapping<Ctx>>> lookups;
    private final Ctx ctx;

    private RegexRouter(Builder<Ctx> builder) {
        this.ctx = builder.context;
        final var mappings = builder.mappings;
        this.lookups = new EnumMap<>(HttpMethod.class);
        for (final var method : HttpMethod.values()) {
            this.lookups.put(method, new ArrayList<>());
        }
        for (final var mapping : mappings) {
            for (final var method : mapping.methods) {
                this.lookups.get(method).add(new Mapping<>(mapping.routePattern(), mapping.handler()));
            }
        }
    }

    public static Builder<?> builder() {
        return new Builder<>();
    }

    public static <Ctx> Builder<Ctx> builder(Ctx context) {
        return new Builder<>(context);
    }

    public interface HandlerTakingContext<Ctx> {
        IntoResponse handle(Ctx context, Request request) throws IOException;
    }

    public interface HandlerTakingContextAndRouteParams<Ctx> {
        IntoResponse handle(Ctx context, RouteParams routeParams, Request request) throws IOException;
    }

    /**
     * Takes a matcher and provides an implementation of RouteParams on top of it.
     *
     * Makes the assumption that it takes ownership of the matcher and will be exposed only via
     * the interface, so the mutability of the matcher is not relevant.
     */
    private record MatcherRouteParams(Matcher matcher) implements RouteParams {
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

    @Override
    public Optional<Handler> handlerForRequest(Request request) {
        final var lookup = this.lookups.get(request.requestMethod());
        for (final var mapping : lookup) {
            final var pattern = mapping.routePattern();
            final var matcher = pattern.matcher(request.uri());
            if (matcher.matches()) {
                return Optional.of(req -> mapping.handler().handle(
                        this.ctx,
                        new MatcherRouteParams(matcher),
                        req)
                );
            }
        }
        return Optional.empty();
    }

    private record Mapping<Ctx>(
            Pattern routePattern,
            HandlerTakingContextAndRouteParams<Ctx> handler
    ) {}
    private record MappingWithMethods<Ctx>(
            EnumSet<HttpMethod> methods,
            Pattern routePattern,
            HandlerTakingContextAndRouteParams<Ctx> handler
    ) {}

    public static final class Builder<Ctx> {
        private final List<MappingWithMethods<Ctx>> mappings;
        private final Ctx context;

        private Builder() {
            this(null);
        }

        private Builder(Ctx context) {
            this.mappings = new ArrayList<>();
            this.context = context;
        }

        public Builder<Ctx> addMapping(
                HttpMethod method,
                Pattern routePattern,
                Handler handler
        ) {
            this.addMapping(EnumSet.of(method), routePattern, handler);
            return this;
        }

        public Builder<Ctx> addMapping(
                EnumSet<HttpMethod> methods,
                Pattern routePattern,
                Handler handler
        ) {
            this.addMapping(methods, routePattern, (ctx, routeParams, request) -> handler.handle(request));
            return this;
        }

        public Builder<Ctx> addMapping(
                HttpMethod method,
                Pattern routePattern,
                HandlerTakingContext<? super Ctx> handler
        ) {
            this.addMapping(EnumSet.of(method), routePattern, handler);
            return this;
        }

        public Builder<Ctx> addMapping(
                EnumSet<HttpMethod> methods,
                Pattern routePattern,
                HandlerTakingContext<? super Ctx> handler
        ) {
            this.addMapping(methods, routePattern, (ctx, routeParams, request) -> handler.handle(ctx, request));
            return this;
        }

        public Builder<Ctx> addMapping(
                HttpMethod method,
                Pattern routePattern,
                HandlerTakingContextAndRouteParams<? super Ctx> handler
        ) {
            this.addMapping(EnumSet.of(method), routePattern, handler);
            return this;
        }

        public Builder<Ctx> addMapping(
                EnumSet<HttpMethod> methods,
                Pattern routePattern,
                HandlerTakingContextAndRouteParams<? super Ctx> handler
        ) {
            Objects.requireNonNull(methods);
            Objects.requireNonNull(routePattern);
            Objects.requireNonNull(handler);

            if (!methods.isEmpty()) {
                this.mappings.add(new MappingWithMethods<>(
                        EnumSet.copyOf(methods),
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

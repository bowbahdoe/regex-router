package dev.mccue.regexrouter;

import java.util.Optional;

public sealed interface RouteParams permits RegexRouter.MatcherRouteParams {
    Optional<String> positionalParameter(int pos);
    Optional<String> namedParameter(String name);
}
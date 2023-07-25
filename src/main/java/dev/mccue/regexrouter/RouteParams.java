package dev.mccue.regexrouter;

import java.util.Optional;

/**
 * Parameters extracted from a {@link dev.mccue.rosie.Request}
 */
public sealed interface RouteParams permits RegexRouter.MatcherRouteParams {
    /**
     * Retrieves a positional parameter.
     * @param pos The position of the parameter.
     * @return The parameter, if there is one.
     */
    Optional<String> positionalParameter(int pos);

    /**
     * Retrieves a named parameter.
     * @param name The name of the parameter.
     * @return The parameter, if there is one.
     */
    Optional<String> namedParameter(String name);
}
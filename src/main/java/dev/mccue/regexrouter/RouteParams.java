package dev.mccue.regexrouter;

import java.util.Optional;

public interface RouteParams {
    Optional<String> positionalParameter(int pos);
    Optional<String> namedParameter(String name);
}
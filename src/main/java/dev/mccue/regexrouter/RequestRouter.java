package dev.mccue.regexrouter;

import dev.mccue.rosie.Handler;
import dev.mccue.rosie.Request;
import java.util.Optional;

public interface RequestRouter {
    Optional<Handler> handlerForRequest(Request request);
}

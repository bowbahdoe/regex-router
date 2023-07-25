import org.jspecify.annotations.NullMarked;

/**
 * See {@link dev.mccue.regexrouter.RegexRouter}.
 */
@NullMarked
module dev.mccue.regexrouter {
    requires transitive dev.mccue.rosie;

    requires static org.jspecify;

    exports dev.mccue.regexrouter;
}
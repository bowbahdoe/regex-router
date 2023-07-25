import org.jspecify.annotations.NullMarked;

@NullMarked
module dev.mccue.regexrouter {
    requires transitive dev.mccue.rosie;

    requires static org.jspecify;

    exports dev.mccue.regexrouter;
}
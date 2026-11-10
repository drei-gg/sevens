// Contract (PLAN §5.4). WP 1.5 implements the body; the signature is frozen.

public enum PayloadEncoder {
    /// The text to put in a generated code. Round-trips through the parsers in this folder.
    public static func encode(_ r: MakeRequest) -> String { fatalError("WP 1.5") }
}

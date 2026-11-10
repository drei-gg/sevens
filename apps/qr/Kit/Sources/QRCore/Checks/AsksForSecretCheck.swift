import Foundation

/// SPEC §6 `asksForSecret`: `args["kind"]` is `password`, `code` or `card`.
enum AsksForSecretCheck {
    private static let passwordAutocomplete: Set<String> = ["current-password", "new-password"]
    private static let codeAutocomplete: Set<String> = ["one-time-code"]
    private static let cardAutocomplete: Set<String> = ["cc-number", "cc-csc"]

    static func kind(of inputs: [HTMLSniff.Input]) -> String? {
        for input in inputs {
            let type = input.type?.lowercased() ?? ""
            let auto = input.autocomplete?.lowercased().split(separator: " ").map(String.init) ?? []
            if type == "password" || auto.contains(where: passwordAutocomplete.contains) { return "password" }
            if auto.contains(where: codeAutocomplete.contains) { return "code" }
            if auto.contains(where: cardAutocomplete.contains) { return "card" }
            if type == "hidden" { continue }
            let hay = [input.name, input.id, input.placeholder].compactMap { $0?.lowercased() }
            for h in hay {
                if h.contains("card number") || h.contains("cardnumber") || h.contains("cvv") || h.contains("cvc") { return "card" }
                if h.contains("otp") || h.contains("mpin") || h.contains("passcode") || h.contains("verification code")
                    || containsWord("pin", in: h) { return "code" }
            }
        }
        return nil
    }

    /// `\bpin\b`
    static func containsWord(_ word: String, in text: String) -> Bool {
        let s = Array(text.unicodeScalars), w = Array(word.unicodeScalars)
        guard s.count >= w.count else { return false }
        func isWord(_ c: Unicode.Scalar) -> Bool { c.properties.isAlphabetic || c.properties.numericType != nil || c == "_" }
        for i in 0...(s.count - w.count) where Array(s[i..<(i + w.count)]) == w {
            if i > 0, isWord(s[i - 1]) { continue }
            if i + w.count < s.count, isWord(s[i + w.count]) { continue }
            return true
        }
        return false
    }
}

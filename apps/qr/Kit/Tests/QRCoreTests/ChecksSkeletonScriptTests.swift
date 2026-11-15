import Foundation
import Testing
@testable import QRCore

@Suite struct ChecksSkeletonTests {
    let skeleton = Skeleton(confusables: BundledData.shared.confusables)

    @Test func checksSkeletonMapsConfusables() {
        #expect(skeleton.of("paypa1") == skeleton.of("paypal"))
        #expect(skeleton.of("rnicrosoft") == skeleton.of("microsoft"))
        #expect(skeleton.of("g00gle") == skeleton.of("google"))
        #expect(skeleton.of("vvikipedia") == skeleton.of("wikipedia"))
        #expect(skeleton.of("\u{0430}\u{0440}\u{0440}\u{04CF}\u{0435}") == skeleton.of("apple"))
        #expect(skeleton.of("\u{FF50}\u{FF41}\u{FF59}\u{FF50}\u{FF41}\u{FF4C}") == skeleton.of("paypal"))
    }

    @Test func checksSkeletonStripsHyphensAndLowercases() {
        #expect(skeleton.of("Pay-Pal") == skeleton.of("paypal"))
        #expect(skeleton.of("a-b-c") == "abc")
    }

    @Test func checksSkeletonLongestKeyFirst() {
        // "rn" is one key; the single-letter "r" and "n" have no entries, so rn must collapse as a pair.
        #expect(skeleton.of("rn") == "m")
        #expect(skeleton.of("vv") == "w")
        #expect(skeleton.of("rnn") == "mn")
    }

    @Test func checksSkeletonDifferentWordsStayDifferent() {
        #expect(skeleton.of("example") != skeleton.of("paypal"))
        #expect(skeleton.of("") == "")
    }
}

@Suite struct ChecksScriptMixTests {
    func mixed(_ s: String) -> Bool { ScriptMix.isMixed(ScriptMix.scripts(in: s)) }

    @Test func checksScriptMixSingleScriptsAreFine() {
        for s in ["example", "b\u{00FC}cher", "\u{043F}\u{0440}\u{0438}\u{043C}\u{0435}\u{0440}", "\u{03B1}\u{03B2}\u{03B3}", "\u{0645}\u{062B}\u{0627}\u{0644}",
                  "\u{4F8B}\u{3048}", "\u{D55C}\u{AD6D}", "my-site-2026", "123", "\u{0E15}\u{0E31}\u{0E27}\u{0E2D}\u{0E22}\u{0E48}\u{0E32}\u{0E07}"] {
            #expect(!mixed(s), "\(s)")
        }
    }

    @Test func checksScriptMixAllowedCombinations() {
        #expect(!mixed("shop\u{4F8B}\u{3048}"))               // Latin + Han + Hiragana
        #expect(!mixed("abc\u{30AB}\u{30BF}\u{30AB}\u{30CA}\u{6F22}")) // Latin + Katakana + Han
        #expect(!mixed("abc\u{D55C}\u{AD6D}\u{6F22}"))        // Latin + Hangul + Han
        #expect(!mixed("abc\u{3105}\u{6F22}"))                // Latin + Han + Bopomofo
    }

    @Test func checksScriptMixForbiddenCombinations() {
        #expect(mixed("ex\u{0430}mple"))                       // Latin + Cyrillic
        #expect(mixed("g\u{03BF}\u{03BF}d"))                   // Latin + Greek
        #expect(mixed("abc\u{0645}"))                          // Latin + Arabic
        #expect(mixed("\u{043F}\u{03B1}"))                     // Cyrillic + Greek
        #expect(mixed("\u{D55C}\u{3042}"))                     // Hangul + Hiragana
        #expect(mixed("abc\u{D55C}\u{3042}"))
        #expect(mixed("abc\u{1200}"))                          // Latin + a script outside the table ("other")
    }

    @Test func checksScriptMixIgnoresDigitsHyphensAndMarks() {
        #expect(!mixed("\u{043F}\u{0440}-2026"))
        #expect(!mixed("e\u{0301}cole"))                       // combining acute is Inherited
    }
}

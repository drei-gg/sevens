import Testing
@testable import QRCore

@Suite struct EMVCRC16Tests {
    @Test func checkValue() {
        #expect(CRC16.ccittFalse(Array("123456789".utf8)) == 0x29B1)
    }

    @Test func emptyIsInit() {
        #expect(CRC16.ccittFalse([UInt8]()) == 0xFFFF)
    }

    /// The sample payload from the EMV QR Code Specification for Payment Systems (Merchant-Presented Mode).
    @Test func publishedSample() {
        let body = "00020101021229300012D156000000000510A93FO3230Q31280012D15600000001030812345678520441115802CN5914BEST TRANSPORT6007BEIJING64200002ZH0104最佳运输0202北京540523.7253031565502016233030412340603***0708A60086670902ME91320016A0112233449988770708123456786304"
        #expect(String(format4(CRC16.ccittFalse(Array(body.utf8)))) == "A13A")
    }

    private func format4(_ v: UInt16) -> String {
        let s = String(v, radix: 16, uppercase: true)
        return String(repeating: "0", count: 4 - s.count) + s
    }
}

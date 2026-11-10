enum PaymentChecksumCheck {
    static func signal(_ info: PaymentInfo) -> Signal? {
        info.checksum == .invalid ? Signal(.paymentChecksumFail, ["format": info.format.rawValue]) : nil
    }
}

/// Every offline check that applies to an http/https `WebAddress`, in stable order.
enum LinkChecks {
    static func signals(for address: WebAddress, _ ctx: CheckContext) -> [Signal] {
        let link = address.link
        let data = ctx.data
        var out: [Signal] = []
        func add(_ s: Signal?) { if let s { out.append(s) } }
        add(UserinfoTrickCheck.signal(link))
        add(MixedScriptCheck.signal(address))
        add(LookalikeBrandCheck.signal(link, ctx.brands))
        add(BrandInDomainCheck.signal(address, ctx.brands, riskyTLDs: ctx.riskyTLDs))
        add(NearBrandCheck.signal(link, ctx.brands))
        add(IPHostCheck.signal(address))
        add(ShortenerCheck.signal(link, data.shorteners))
        add(PlainHTTPCheck.signal(link))
        add(RiskyTLDCheck.signal(link, ctx.riskyTLDs))
        add(UnknownEndingCheck.signal(address))
        add(DeepSubdomainsCheck.signal(link))
        add(ObfuscatedPathCheck.signal(link, baitWords: ctx.baitWords))
        add(AssumedHTTPSCheck.signal(address))
        return out.inStableOrder()
    }
}

import Foundation
import StoreKit
import SevenKitCore

/// PLAN §6.10. `Product.products(for:)`, `Transaction.currentEntitlements`, a
/// `Transaction.updates` listener, `AppStore.sync()` for Restore. The rules live in
/// `entitlementFacts(from:products:pendingProductIDs:)`.
///
/// A pending purchase is remembered for this process only; when Apple approves it, the update
/// arrives through `Transaction.updates` and clears it.
@MainActor
public final class StoreKitSource: EntitlementSource {
    private let config: SevenConfig
    private var products: [String: Product] = [:]
    private var pending: Set<String> = []
    private var continuation: AsyncStream<EntitlementFacts>.Continuation?
    private var listener: Task<Void, Never>?

    public init(config: SevenConfig) {
        self.config = config
    }

    public func loadPrices() async -> SevenPrices? {
        let ids = [config.productIDs.trial, config.productIDs.unlock]
        guard let loaded = try? await Product.products(for: ids) else { return nil }
        products = Dictionary(uniqueKeysWithValues: loaded.map { ($0.id, $0) })
        guard let unlock = products[config.productIDs.unlock] else { return nil }
        return SevenPrices(trial: products[config.productIDs.trial]?.displayPrice, unlock: unlock.displayPrice)
    }

    public func currentFacts() async -> EntitlementFacts {
        var records: [TransactionRecord] = []
        for await result in Transaction.currentEntitlements {
            guard case .verified(let transaction) = result else { continue }
            records.append(TransactionRecord(
                productID: transaction.productID,
                purchaseDate: transaction.purchaseDate,
                revocationDate: transaction.revocationDate
            ))
        }
        return entitlementFacts(from: records, products: config.productIDs, pendingProductIDs: pending)
    }

    public func factUpdates() -> AsyncStream<EntitlementFacts> {
        listener?.cancel()
        let (stream, continuation) = AsyncStream.makeStream(of: EntitlementFacts.self)
        self.continuation = continuation
        listener = Task { [weak self] in
            for await result in Transaction.updates {
                guard let self else { return }
                await self.handle(result)
            }
        }
        return stream
    }

    private func handle(_ result: VerificationResult<Transaction>) async {
        guard case .verified(let transaction) = result else { return }
        await transaction.finish()
        pending.remove(transaction.productID)
        await publish()
    }

    private func publish() async {
        continuation?.yield(await currentFacts())
    }

    public func purchaseTrial() async throws -> PurchaseOutcome {
        try await purchase(config.productIDs.trial)
    }

    public func purchaseUnlock() async throws -> PurchaseOutcome {
        try await purchase(config.productIDs.unlock)
    }

    private func purchase(_ id: String) async throws -> PurchaseOutcome {
        if products[id] == nil { _ = await loadPrices() }
        guard let product = products[id] else { throw SevenStoreError.productUnavailable }
        switch try await product.purchase() {
        case .success(let result):
            guard case .verified(let transaction) = result else { throw SevenStoreError.unverified }
            await transaction.finish()
            pending.remove(id)
            await publish()
            return .purchased
        case .pending:
            pending.insert(id)
            await publish()
            return .pending
        case .userCancelled:
            return .cancelled
        @unknown default:
            return .cancelled
        }
    }

    public func restore() async throws {
        try await AppStore.sync()
        await publish()
    }
}

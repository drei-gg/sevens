import Foundation
import FastCore
import SevenCompanion

extension FastModel {
    func startWatch() {
        guard !watchStarted else { return }; watchStarted = true
        companion.receiveRequest = { [weak self] data in
            guard let self else { return Data() }
            return await self.receiveWatch(data)
        }
        companion.activate()
    }
    func publishWatch() {
        guard isReady else { return }
        let reply = FastWatchReply(snapshot: FastWatchSnapshot(archive: archive, canCreateRecords: store.paidFeaturesAvailable))
        if let data = try? FastWatchCodec.encode(reply) { companion.publish(data) }
    }
    func receiveWatch(_ data: Data) async -> Data {
        var reply = FastWatchReply(failure: .malformed)
        do {
            let request = try FastWatchCodec.decode(FastWatchRequest.self, from: data)
            reply.requestID = request.id
            guard request.version == 1 else { throw FastWatchFailure.unsupported }
            guard isReady, store.hasStarted, !inFlight else { throw FastWatchFailure.unavailable }
            let reveal = beginWrite()
            defer { endWrite(reveal) }
            await store.refresh()
            if request.action != .refresh {
                let permitted = store.paidFeaturesAvailable
                let now = Date()
                archive = try await repository.transact(now: now) {
                    try $0.applyWatch(request, canCreateRecords: permitted, now: now)
                }
                await updateServices()
            }
            reply.failure = nil
            reply.snapshot = FastWatchSnapshot(archive: archive, canCreateRecords: store.paidFeaturesAvailable)
        } catch {
            reply.failure = (error as? FastWatchFailure) ?? .saveFailed
            if isReady { reply.snapshot = FastWatchSnapshot(archive: archive, canCreateRecords: store.paidFeaturesAvailable) }
        }
        return (try? FastWatchCodec.encode(reply)) ?? Data()
    }
}

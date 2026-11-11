import Foundation
import AlarmCore

/// Allowlisted fields only: never serialize routines, receipts, errors or history.
public struct AlarmDiagnostics: Codable, Sendable {
    public let formatVersion: Int
    public let generatedAt: Date
    public let appVersion: String
    public let build: String
    public let operatingSystem: String
    public let previewMode: Bool
    public let alarmPermission: String
    public let routineCount: Int
    public let enabledRoutineCount: Int
    public let localReceiptCount: Int
    public let desiredRegistrationCount: Int?
    public let nativeRegistrationCount: Int?
    public let missingRegistrationCount: Int?
    public let scheduleValidationSucceeded: Bool
    public let nativeQuerySucceeded: Bool
    public let hasLocalError: Bool
    public let hasLoadError: Bool
}

extension AlarmModel {
    public func diagnostics() async -> AlarmDiagnostics {
        let desiredIDs = (try? desired()).map { Set($0.map(\.id)) }
        let nativeIDs = try? await driver.identifiers()
        return AlarmDiagnostics(
            formatVersion:1,generatedAt:Date(),
            appVersion:Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "Unknown",
            build:Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "Unknown",
            operatingSystem:ProcessInfo.processInfo.operatingSystemVersionString,
            previewMode:isPreview,alarmPermission:driver.status,
            routineCount:archive.routines.count,
            enabledRoutineCount:archive.routines.filter(\.enabled).count,
            localReceiptCount:archive.installed.count,
            desiredRegistrationCount:desiredIDs?.count,
            nativeRegistrationCount:nativeIDs?.count,
            missingRegistrationCount:desiredIDs.flatMap { desired in nativeIDs.map { desired.subtracting($0).count } },
            scheduleValidationSucceeded:desiredIDs != nil,nativeQuerySucceeded:nativeIDs != nil,
            hasLocalError:error != nil,hasLoadError:loadError != nil)
    }
    public func diagnosticData() async throws -> Data {
        let report = await diagnostics()
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted,.sortedKeys]
        return try encoder.encode(report)
    }
}

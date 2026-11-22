import CleanerCore

/// Titles for the core's media filters. The rules (unknown is never zero, duration applies to
/// videos only) live in the core's `mediaFiltersMatch`.
extension MediaResolutionFilter {
    static let choices: [Self] = [.any, .atLeast2160, .atLeast1080, .atLeast720, .below720, .unknown]
    var title: String {
        switch self {
        case .any: c("Any dimensions")
        case .atLeast2160: c("4K or larger (2160 px short side)")
        case .atLeast1080: c("At least 1080 px short side")
        case .atLeast720: c("At least 720 px short side")
        case .below720: c("Under 720 px short side")
        case .unknown: c("Dimensions unknown")
        }
    }
}
extension MediaDurationFilter {
    static let choices: [Self] = [.any, .under1Minute, .atLeast1Minute, .atLeast5Minutes, .atLeast30Minutes, .unknown]
    var title: String {
        switch self {
        case .any: c("Any length")
        case .under1Minute: c("Under 1 minute")
        case .atLeast1Minute: c("At least 1 minute")
        case .atLeast5Minutes: c("At least 5 minutes")
        case .atLeast30Minutes: c("At least 30 minutes")
        case .unknown: c("Length unknown")
        }
    }
}

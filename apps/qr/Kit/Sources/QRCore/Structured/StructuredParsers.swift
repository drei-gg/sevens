// WP 1.5 owns this file (and may split it). The signatures below are the seam the
// Classifier (WP 2.1) calls; keep them. Each returns nil when the text isn't that format.
// Links inside (PLAN §6.2 "Embedded links") are found by the Classifier, not here;
// parsers leave `links` empty except ContactInfo/EventInfo `url(s)`, which they copy in.

public enum WiFiParser { public static func parse(_ text: String) -> WiFiInfo? { fatalError("WP 1.5") } }
/// vCard 2.1/3.0/4.0 and MeCard.
public enum ContactParser { public static func parse(_ text: String) -> ContactInfo? { fatalError("WP 1.5") } }
/// BEGIN:VEVENT, or a VCALENDAR containing one (the first VEVENT).
public enum EventParser { public static func parse(_ text: String) -> EventInfo? { fatalError("WP 1.5") } }
/// tel:
public enum PhoneParser { public static func parse(_ text: String) -> PhoneInfo? { fatalError("WP 1.5") } }
/// sms: / smsto: / SMSTO:
public enum SMSParser { public static func parse(_ text: String) -> SMSInfo? { fatalError("WP 1.5") } }
/// mailto: / MATMSG:
public enum EmailParser { public static func parse(_ text: String) -> EmailInfo? { fatalError("WP 1.5") } }
/// geo:
public enum GeoParser { public static func parse(_ text: String) -> GeoInfo? { fatalError("WP 1.5") } }

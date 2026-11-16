package gg.seven.qr

import gg.seven.kit.BeforeYouBuyContent
import gg.seven.kit.SevenContent
import gg.seven.kit.TrialEndedContent
import gg.seven.kit.UnlockContent

/*
 * QR Code Kit's words for the brand moments, from the iOS gate copy. "Before you buy" names what
 * Android already does built in (never another third-party app), so it differs from iOS.
 */
internal val QrContent =
    SevenContent(
        beforeYouBuy =
            BeforeYouBuyContent(
                title = "Before you buy",
                paragraphs =
                    listOf(
                        BeforeYouBuyContent.Paragraph(
                            "Your phone already scans QR codes. Point the Camera at one, or use Google Lens. If that’s all you need, delete this app and keep your money.",
                        ),
                        BeforeYouBuyContent.Paragraph(
                            "What this adds: it checks where a code really goes before you open it, shows who a payment code pays, makes codes from anything you share, and remembers what you scanned. Free for 7 days from when you tap Start, no card. Then {price} once, if you want to keep it.",
                            withoutPrice =
                                "What this adds: it checks where a code really goes before you open it, shows who a payment code pays, makes codes from anything you share, and remembers what you scanned. Free for 7 days from when you tap Start, no card. Then a one-time price, if you want to keep it.",
                        ),
                    ),
                lookAroundLabel = "Just scan for free",
            ),
        unlock =
            UnlockContent(
                appName = "QR Code Kit",
                features =
                    listOf(
                        "Peek: follow a link’s redirects and see what its page asks for before you open it",
                        "Make QR codes from text, links, Wi-Fi, contacts and more, and export them",
                        "Make QR code from the share menu in any app",
                        "History: save what you scan and make, with search, notes and export",
                    ),
            ),
        trialEnded =
            TrialEndedContent(
                stillFree =
                    listOf(
                        "Scanning from the camera, photos and clipboard",
                        "The verdict and every scam check",
                        "Payment-code details",
                        "Opening links",
                        "Check this code from the share menu",
                        "Reading and exporting the history you already saved",
                    ),
                unlockKeeps =
                    listOf(
                        "Peek",
                        "Make and export",
                        "Make QR code from the share menu",
                        "Saving new scans to History",
                    ),
            ),
        notStartedStatus = "Free trial not started. Scanning stays free.",
    )

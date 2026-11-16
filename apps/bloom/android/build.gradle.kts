extra["sevenApp"] = "bloom"

extra["sevenGenericName"] = "Cycle & Health"

apply(plugin = "seven.base-app")

// The calendar engine is a Rust path dependency. Changes must invalidate both native tasks.
tasks.named("rustAndroid") { inputs.dir(projectDir.resolve("../../alarm/core/src")) }

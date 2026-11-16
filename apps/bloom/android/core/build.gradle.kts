plugins { id("seven.rust-core") }

tasks.named("rustBindings") { inputs.dir(projectDir.resolve("../../../alarm/core/src")) }

// Plugin versions are pinned once in buildSrc, including the reusable app convention.
subprojects { dependencyLocking { lockAllConfigurations() } }

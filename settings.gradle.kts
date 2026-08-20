rootProject.name = "synanton"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        mavenCentral()
    }
}

// Active Java modules - Phase 3 adds control-plane, synanton-mcp, and synflux-router.
// v1.20 adds gpu-contract (synanton.gpu.v1 proto stubs) and gpu-gateway (GPU Execution Plane).
include(
    "java:shared:common",
    "java:security",
    "java:topology",
    "java:syntology",
    "java:control-plane",
    "java:synanton-mcp",
    "java:ingestion-cache",
    "java:synvault",
    "java:synflux",
    "java:synflux-router",
    "java:synquest",
    "java:relix",
    "java:planner",
    "java:gateway",
    "java:synapt",
    "java:synanton-llm-client",
    "java:gpu-contract",
    "java:gpu-gateway",
)

// Give each project a flat, predictable path on disk (e.g. java/security)
// instead of the default :java:security style nested layout.
rootProject.children.forEach { renameProjectDir(it) }

fun renameProjectDir(project: ProjectDescriptor) {
    // No-op for now; kept as the place to customise project dirs later.
    project.children.forEach { renameProjectDir(it) }
}

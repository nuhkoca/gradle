// tag::plugins_block[]
plugins {
    id("java-gradle-plugin")                          // <1>
    id("maven-publish")                               // <2>
    id("com.gradle.plugin-publish") version "0.16.0"  // <3>
}
// end::plugins_block[]

// tag::gradle-plugin[]
group = "org.myorg" // <1>
version = "1.0"     // <2>

gradlePlugin {
    plugins { // <3>
        create("greetingsPlugin") { // <4>
            id = "<your plugin identifier>" // <5>
            displayName = "<short displayable name for plugin>" // <6>
            description = "<Good human-readable description of what your plugin is about>" // <7>
            implementationClass = "<your plugin class>"
        }
    }
}
// end::gradle-plugin[]

// tag::plugin_bundle[]
pluginBundle {
    website = "<substitute your project website>"   // <1>
    vcsUrl = "<uri to project source repository>"   // <2>
    tags = listOf("tags", "for", "your", "plugins") // <3>
}
// end::plugin_bundle[]

// tag::local_repository[]
publishing {
    repositories {
        maven {
            name = "localPluginRepository"
            url = uri("../local-plugin-repository")
        }
    }
}
// end::local_repository[]

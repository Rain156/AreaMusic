package datura.gradle

import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class VersionNamespaceWiringTest {
    @Test
    void minecraft1201VersionPropertiesUseAnExplicitNamespace() {
        Properties properties = loadProperties()

        Map<String, String> expected = [
                mc1_20_1_minecraft_version      : '1.20.1',
                mc1_20_1_minecraft_version_range: '[1.20.1,1.21)',
                mc1_20_1_forge_version          : '47.4.22',
                mc1_20_1_forge_version_range    : '[47,)',
                mc1_20_1_loader_version_range   : '[47,)',
                mc1_20_1_mapping_channel        : 'official',
                mc1_20_1_mapping_version        : '1.20.1',
                mc1_20_1_fabric_loader_version  : '0.19.3',
                mc1_20_1_fabric_api_version     : '0.92.11+1.20.1'
        ]
        expected.each { key, value ->
            assertEquals(value, properties.getProperty(key), key)
        }

        for (String ambiguousKey : [
                'minecraft_version',
                'minecraft_version_range',
                'forge_version',
                'forge_version_range',
                'loader_version_range',
                'mapping_channel',
                'mapping_version',
                'fabric_loader_version',
                'fabric_api_version'
        ]) {
            assertFalse(properties.containsKey(ambiguousKey), ambiguousKey)
        }
    }

    @Test
    void minecraft1211VersionPropertiesReserveTheConfirmedLoaderMatrix() {
        Properties properties = loadProperties()

        Map<String, String> expected = [
                mc1_21_1_minecraft_version      : '1.21.1',
                mc1_21_1_minecraft_version_range: '[1.21.1,1.22)',
                mc1_21_1_mapping_channel        : 'official',
                mc1_21_1_mapping_version        : '1.21.1',
                mc1_21_1_neoform_version        : '1.21.1-20240808.144430',
                mc1_21_1_forge_version          : '52.1.16',
                mc1_21_1_forge_version_range    : '[52,)',
                mc1_21_1_loader_version_range   : '[52,)',
                mc1_21_1_neoforge_version       : '21.1.244',
                mc1_21_1_fabric_loader_version  : '0.19.3',
                mc1_21_1_fabric_api_version     : '0.116.14+1.21.1',
                mc1_21_1_loom_version           : '1.17.17'
        ]
        expected.each { key, value ->
            assertEquals(value, properties.getProperty(key), key)
        }
    }

    @Test
    void existingMinecraft1201ModulesDoNotReadAmbiguousVersionPropertyNames() {
        Path root = findRepositoryRoot()
        List<Path> versionConsumers = [
                root.resolve('platforms/1.20.1/common/build.gradle'),
                root.resolve('platforms/1.20.1/fabric/build.gradle'),
                root.resolve('platforms/1.20.1/fabric/src/main/resources/fabric.mod.json'),
                root.resolve('platforms/1.20.1/forge/build.gradle'),
                root.resolve('platforms/1.20.1/forge/src/main/resources/META-INF/mods.toml')
        ]
        List<String> ambiguousNames = [
                'minecraft_version',
                'minecraft_version_range',
                'forge_version',
                'forge_version_range',
                'loader_version_range',
                'mapping_channel',
                'mapping_version',
                'fabric_loader_version',
                'fabric_api_version'
        ]

        versionConsumers.each { file ->
            String text = Files.readString(file)
            ambiguousNames.each { propertyName ->
                def matcher = text =~ /(?<![A-Za-z0-9_])${propertyName}(?![A-Za-z0-9_])/
                assertFalse(matcher.find(),
                        "${root.relativize(file)} must not read ambiguous ${propertyName}".toString())
            }
        }

        for (String globalModProperty : [
                'mod_id', 'mod_name', 'mod_license', 'mod_version',
                'mod_group_id', 'mod_authors', 'mod_description'
        ]) {
            assertTrue(loadProperties().containsKey(globalModProperty), globalModProperty)
        }
    }

    private static Properties loadProperties() {
        Properties properties = new Properties()
        Files.newBufferedReader(findRepositoryRoot().resolve('gradle.properties')).withCloseable {
            properties.load(it)
        }
        return properties
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty('user.dir')).toAbsolutePath()
        while (current != null) {
            if (Files.isRegularFile(current.resolve('settings.gradle'))
                    && Files.isDirectory(current.resolve('buildSrc'))) {
                return current
            }
            current = current.parent
        }
        throw new AssertionError('Could not locate repository root')
    }
}

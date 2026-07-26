package datura.gradle

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

import javax.inject.Inject

class ProductionRootsJvmArgumentProvider implements CommandLineArgumentProvider {
    private final Property<String> propertyName
    private final ConfigurableFileCollection productionRoots

    @Inject
    ProductionRootsJvmArgumentProvider(ObjectFactory objects) {
        propertyName = objects.property(String)
        productionRoots = objects.fileCollection()
    }

    @Input
    Property<String> getPropertyName() {
        return propertyName
    }

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    ConfigurableFileCollection getProductionRoots() {
        return productionRoots
    }

    @Override
    Iterable<String> asArguments() {
        String roots = productionRoots.files.collect { File root ->
            root.toPath().toAbsolutePath().normalize().toString()
        }.join(File.pathSeparator)
        return ["-D${propertyName.get()}=${roots}"]
    }
}

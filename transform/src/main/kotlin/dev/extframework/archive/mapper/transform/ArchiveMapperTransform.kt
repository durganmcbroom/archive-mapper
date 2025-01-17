package dev.extframework.archive.mapper.transform

import dev.extframework.archive.mapper.ArchiveMapping
import dev.extframework.archive.mapper.ClassIdentifier
import dev.extframework.archive.mapper.MethodIdentifier
import dev.extframework.archives.ArchiveReference
import dev.extframework.archives.ArchiveTree
import dev.extframework.archives.Archives
import dev.extframework.archives.extension.toMethod
import dev.extframework.archives.transform.AwareClassWriter
import dev.extframework.archives.transform.TransformerConfig
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.ParameterNode
import java.io.ByteArrayInputStream
import java.io.InputStream

internal fun ClassInheritancePath.toCheck(): List<String> {
    return listOf(name) + (superClass?.toCheck() ?: listOf()) + interfaces.flatMap { it.toCheck() }
}

public fun mappingTransformConfigFor(
    mappings: ArchiveMapping,
    srcNamespace: String,
    dstNamespace: String,
    inheritanceTree: ClassInheritanceTree,
): TransformerConfig.Mutable {
    val remapper = ArchiveRemapper(
        mappings, srcNamespace, dstNamespace, inheritanceTree
    )

    return TransformerConfig.of {
        transformClass {
            val newNode = ClassNode()
            val classRemapper = ClassRemapper(newNode, remapper)
            it.accept(classRemapper)

            newNode.methods.forEach { method ->
                val classNode = mappings.classes[ClassIdentifier(newNode.name, dstNamespace)] ?: return@forEach

                val mappingNode = classNode.methods[MethodIdentifier(
                    method.toMethod(),
                    dstNamespace,
                )] ?: return@forEach

                if (mappingNode.parameterNames.isNotEmpty()) {
                    method.parameters = ArrayList()

                    var lastIndex = 0

                    mappingNode.parameterNames.forEach { (i, it) ->
                        (lastIndex until i).forEach { pI ->
                            method.parameters.add(ParameterNode("arg${pI}", Opcodes.ACC_SYNTHETIC))
                        }
                        lastIndex = i + 1

                       method.parameters.add(ParameterNode(
                            it[dstNamespace] ?: "arg${i}",
                            Opcodes.ACC_SYNTHETIC,
                        ))
                    }
                }
            }

            newNode
        }
    }
}


// Archive transforming context
//public data class ArchiveTransformerContext(
////    val theArchive: ArchiveReference,
////    val dependencies: List<ArchiveTree>,
//
//    val mappings: ArchiveMapping,
//    val fromNamespace: String,
//    val toNamespace: String,
//    val inheritanceTree: ClassInheritanceTree
//)

internal fun InputStream.classNode(parsingOptions: Int = 0): ClassNode {
    val node = ClassNode()
    ClassReader(this).accept(node, parsingOptions)
    return node
}

private fun ArchiveReference.transformAndWriteClass(
    name: String, // Fake location
    mappings: ArchiveMapping,
    srcNamespace: String,
    dstNamespace: String,
    archive: ArchiveReference,
    dependencies: List<ArchiveTree>,
    config: TransformerConfig
) {
    val entry = checkNotNull(
        reader["$name.class"]
    ) { "Failed to find class '$name' when transforming archive: '${this.name}'" }

    val resolve = Archives.resolve(
        ClassReader(entry.open()),
        config,
        MappingAwareClassWriter(
            config,
            mappings, srcNamespace, dstNamespace, archive, dependencies
        ),
        ClassReader.EXPAND_FRAMES
    )

    val transformedName = mappings.mapClassName(name, srcNamespace, dstNamespace) ?: name
    val transformedEntry = ArchiveReference.Entry(
        "$transformedName.class",
        false,
        this
    ) {
        ByteArrayInputStream(resolve)
    }

    if (transformedName != name)
        writer.remove("$name.class")
    writer.put(transformedEntry)
}

private class MappingAwareClassWriter(
    private val config: TransformerConfig,
    private val mappings: ArchiveMapping,
    private val srcNamespace: String,
    private val dstNamespace: String,
    private val archive: ArchiveReference,
    dependencies: List<ArchiveTree>,
) : AwareClassWriter(
    dependencies,
    0,
) {
    private fun getMappedNode(name: String): HierarchyNode? {
        var node = archive.reader["$name.class"]
            ?.open()?.classNode(ClassReader.EXPAND_FRAMES) ?: run {
            val otherName =
                // Switch it as we want to go the other direction
                mappings.mapClassName(name, srcNamespace, dstNamespace) ?: return@run null
            archive.reader["$otherName.class"]?.open()
        }?.classNode(ClassReader.EXPAND_FRAMES) ?: return null

        node = config.ct(node)
        node.methods = node.methods.map {
            config.mt.invoke(it)
        }
        node.fields = node.fields.map {
            config.ft.invoke(it)
        }

        return UnloadedClassNode(node)
    }

    override fun loadType(name: String): HierarchyNode {
        return getMappedNode(name) ?: super.loadType(name)
    }
}

public typealias ClassInheritanceTree = Map<String, ClassInheritancePath>

public data class ClassInheritancePath(
    val name: String,
    val superClass: ClassInheritancePath?,
    val interfaces: List<ClassInheritancePath>
)

public fun createFakeInheritancePath(
    entry: ArchiveReference.Entry,
    reader: ArchiveReference.Reader
): ClassInheritancePath {
    val node = entry.open().classNode()

    return ClassInheritancePath(
        node.name,

        reader[node.superName + ".class"]?.let { createFakeInheritancePath(it, reader) },
        node.interfaces?.mapNotNull { n ->
            reader["$n.class"]?.let { createFakeInheritancePath(it, reader) }
        } ?: listOf()
    )
}

public class DelegatingArchiveReader(
    private val archives: List<ArchiveReference>
) : ArchiveReference.Reader {
    override fun entries(): Sequence<ArchiveReference.Entry> = sequence {
        archives.forEach {
            yieldAll(it.reader.entries())
        }
    }

    override fun of(name: String): ArchiveReference.Entry? {
        return archives.firstNotNullOfOrNull {
            it.reader.of(name)
        }
    }
}

public fun createFakeInheritanceTree(reader: ArchiveReference.Reader): ClassInheritanceTree {
    return reader.entries()
        .filterNot(ArchiveReference.Entry::isDirectory)
        .filter { it.name.endsWith(".class") }
        .map { createFakeInheritancePath(it, reader) }
        .associateBy { it.name }
}

public fun transformArchive(
    archive: ArchiveReference,
    dependencies: List<ArchiveTree>,
    mappings: ArchiveMapping,
    fromNamespace: String,
    toNamespace: String,
) {
    val inheritanceTree: ClassInheritanceTree = createFakeInheritanceTree(archive.reader)

    val config = mappingTransformConfigFor(
        mappings, fromNamespace, toNamespace, inheritanceTree
    )

    archive.reader.entries()
        .filter { it.name.endsWith(".class") }
        .forEach { e ->
            archive.transformAndWriteClass(
                e.name.removeSuffix(".class"),
                mappings,
                fromNamespace,
                toNamespace,
                archive,
                dependencies,
                config
            )
        }
}
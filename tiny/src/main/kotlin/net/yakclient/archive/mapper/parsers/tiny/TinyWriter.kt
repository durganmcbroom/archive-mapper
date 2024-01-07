@file:JvmName("TinyWriter")

package net.yakclient.archive.mapper.parsers.tiny

import net.fabricmc.mappingio.MappedElementKind
import net.fabricmc.mappingio.MappingVisitor
import net.yakclient.archive.mapper.ArchiveMapping
import net.yakclient.archive.mapper.MappingIdentifier
import net.yakclient.archive.mapper.MappingNode


// Namespace 0 = dstNamespace
public fun <T> write(writer1: T, mappings: ArchiveMapping, srcNamespace: String, dstNamespace: String)
        where T : MappingVisitor, T : AutoCloseable {

    writer1.use { writer ->
        writer.visitHeader()
        writer.visitNamespaces(srcNamespace, listOf(dstNamespace))
        writer.visitContent()
        mappings.classes.values.forEach { clazz ->
            writer.visitClass(clazz.getIdentifier(srcNamespace)?.name)
            writer.visitDstName(MappedElementKind.CLASS, 0, clazz.getIdentifier(dstNamespace)?.name)
            writer.visitElementContent(MappedElementKind.CLASS)

            clazz.methods.values.forEach { method ->
                writer.visitMethod(method.getIdentifier(srcNamespace)?.name,
                    "(" + (method.getIdentifier(srcNamespace)?.parameters
                        ?: listOf()).joinToString(separator = "") {
                        it.descriptor
                    } + ")" + method.returnType[srcNamespace]!!.descriptor)
                writer.visitDstName(MappedElementKind.METHOD, 0, method.getIdentifier(dstNamespace)?.name)
                writer.visitDstDesc(MappedElementKind.METHOD,
                    0,
                    "(" + (method.getIdentifier(dstNamespace)?.parameters
                        ?: listOf()).joinToString(separator = "") {
                        it.descriptor
                    } + ")" + method.returnType[dstNamespace]!!.descriptor)

                writer.visitElementContent(MappedElementKind.METHOD)

//                (method.getIdentifier(srcNamespace)?.parameters ?: listOf()).zip(
//                    method.getIdentifier(
//                        dstNamespace
//                    )?.parameters ?: listOf()
//                ).withIndex()
//                    .forEach { (i, pair) ->
//                        val (r, f) = pair
//                        writer.visitMethodArg(i, i, r.descriptor)
//                        writer.visitDstName(MappedElementKind.METHOD_ARG, 0, f.descriptor)
//                        writer.visitElementContent(MappedElementKind.METHOD_ARG)
//                    }

            }

            clazz.fields.values.forEach { field ->
                writer.visitField(field.getIdentifier(srcNamespace)?.name, field.type[srcNamespace]!!.descriptor)
                writer.visitDstName(MappedElementKind.FIELD, 0, field.getIdentifier(dstNamespace)?.name)
                writer.visitDstDesc(MappedElementKind.FIELD, 0, field.type[dstNamespace]!!.descriptor)
                writer.visitElementContent(MappedElementKind.FIELD)
            }
        }
    }
}
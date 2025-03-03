package sushi.hardcore.droidfs.explorers

import sushi.hardcore.droidfs.collation.getCollationKeyForFileName
import sushi.hardcore.droidfs.util.PathUtils
import java.text.Collator
import java.util.*

class ExplorerElement(val name: String, val elementType: Short, var size: Long = -1, mTime: Long = -1, val parentPath: String) {
    val mTime = Date((mTime * 1000).toString().toLong())
    val fullPath: String = PathUtils.pathJoin(parentPath, name)
    val collationKey = Collator.getInstance().getCollationKeyForFileName(fullPath)

    val isDirectory: Boolean
        get() = elementType.toInt() == DIRECTORY_TYPE

    val isParentFolder: Boolean
        get() = elementType.toInt() == PARENT_FOLDER_TYPE

    val isRegularFile: Boolean
        get() = elementType.toInt() == REGULAR_FILE_TYPE

    companion object {
        const val DIRECTORY_TYPE = 0
        const val PARENT_FOLDER_TYPE = -1
        const val REGULAR_FILE_TYPE = 1

        @JvmStatic
        //this function is needed because I had some problems calling the constructor from JNI, probably due to arguments with default values
        fun new(name: String, elementType: Short, size: Long, mTime: Long, parentPath: String): ExplorerElement {
            return ExplorerElement(name, elementType, size, mTime, parentPath)
        }

        private fun foldersFirst(a: ExplorerElement, b: ExplorerElement, default: () -> Int): Int {
            return if (a.isDirectory && b.isRegularFile) {
                -1
            } else if (b.isDirectory && a.isRegularFile) {
                1
            } else {
                default()
            }
        }
        private fun doSort(a: ExplorerElement, b: ExplorerElement, foldersFirst: Boolean, sorter: () -> Int): Int {
            return if (b.isParentFolder) {
                1
            } else if (a.isParentFolder) {
                -1
            } else {
                if (foldersFirst) {
                    foldersFirst(a, b, sorter)
                } else {
                    sorter()
                }
            }
        }
        fun sortBy(sortOrder: String, foldersFirst: Boolean, explorerElements: MutableList<ExplorerElement>) {
            when (sortOrder) {
                "name" -> {
                    explorerElements.sortWith { a, b ->
                        doSort(a, b, foldersFirst) { a.collationKey.compareTo(b.collationKey) }
                    }
                }
                "size" -> {
                    explorerElements.sortWith { a, b ->
                        doSort(a, b, foldersFirst) { (a.size - b.size).toInt() }
                    }
                }
                "date" -> {
                    explorerElements.sortWith { a, b ->
                        doSort(a, b, foldersFirst) { a.mTime.compareTo(b.mTime) }
                    }
                }
                "name_desc" -> {
                    explorerElements.sortWith { a, b ->
                        doSort(a, b, foldersFirst) { b.collationKey.compareTo(a.collationKey) }
                    }
                }
                "size_desc" -> {
                    explorerElements.sortWith { a, b ->
                        doSort(a, b, foldersFirst) { (b.size - a.size).toInt() }
                    }
                }
                "date_desc" -> {
                    explorerElements.sortWith { a, b ->
                        doSort(a, b, foldersFirst) { b.mTime.compareTo(a.mTime) }
                    }
                }
            }
        }
    }
}
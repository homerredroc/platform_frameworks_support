package androidx.ui.semantics

import android.annotation.SuppressLint
import androidx.ui.Int32List
import androidx.ui.VoidCallback
import androidx.ui.assert
import androidx.ui.describeEnum
import androidx.ui.engine.geometry.Offset
import androidx.ui.engine.geometry.Rect
import androidx.ui.engine.text.TextDirection
import androidx.ui.foundation.AbstractNode
import androidx.ui.foundation.Key
import androidx.ui.foundation.assertions.FlutterError
import androidx.ui.foundation.diagnostics.DiagnosticLevel
import androidx.ui.foundation.diagnostics.DiagnosticPropertiesBuilder
import androidx.ui.foundation.diagnostics.DiagnosticableTree
import androidx.ui.foundation.diagnostics.DiagnosticsNode
import androidx.ui.foundation.diagnostics.DiagnosticsProperty
import androidx.ui.foundation.diagnostics.DiagnosticsTreeStyle
import androidx.ui.foundation.diagnostics.EnumProperty
import androidx.ui.foundation.diagnostics.FlagProperty
import androidx.ui.foundation.diagnostics.FloatProperty
import androidx.ui.foundation.diagnostics.IterableProperty
import androidx.ui.foundation.diagnostics.MessageProperty
import androidx.ui.foundation.diagnostics.StringProperty
import androidx.ui.runtimeType
import androidx.ui.services.text_editing.TextSelection
import androidx.ui.toStringAsFixed
import androidx.ui.vectormath64.Matrix4
import androidx.ui.vectormath64.Vector3
import androidx.ui.vectormath64.getAsScale
import androidx.ui.vectormath64.getAsTranslation
import androidx.ui.vectormath64.isIdentity
import androidx.ui.vectormath64.matrixEquals

fun _initIdentityTransform(): FloatArray {
    return Matrix4.identity().m4storage.toFloatArray()
}

private val _kEmptyChildList: Int32List = Int32List(0)
private val _kIdentityTransform = _initIdentityTransform()

// /// Converts `point` to the `node`'s parent's coordinate system.
fun _pointInParentCoordinates(node: SemanticsNode, point: Offset): Offset {
    if (node.transform == null) {
        return point
    }
    val vector: Vector3 = Vector3(point.dx, point.dy, 0.0f)
    TODO("Needs Matrix4.transform3")
//    node.transform.transform3(vector);
    return Offset(vector.x, vector.y)
}

/**
 * Sorts `children` using the default sorting algorithm, and returns them as a
 * new list.
 *
 * The algorithm first breaks up children into groups such that no two nodes
 * from different groups overlap vertically. These groups are sorted vertically
 * according to their [_SemanticsSortGroup.startOffset].
 *
 * Within each group, the nodes are sorted using
 * [_SemanticsSortGroup.sortedWithinVerticalGroup].
 *
 * For an illustration of the algorithm see http://bit.ly/flutter-default-traversal.
 */
private fun _childrenInDefaultOrder(
    children: List<SemanticsNode>,
    textDirection: TextDirection
): List<SemanticsNode> {
    val edges: MutableList<_BoxEdge> = mutableListOf()
    for (child in children) {
        edges.add(
            _BoxEdge(
                isLeadingEdge = true,
                offset = _pointInParentCoordinates(child, child.rect.getTopLeft()).dy,
                node = child
            )
        )
        edges.add(
            _BoxEdge(
                isLeadingEdge = false,
                offset = _pointInParentCoordinates(child, child.rect.getBottomRight()).dy,
                node = child
            )
        )
    }
    edges.sort()

    val verticalGroups: MutableList<_SemanticsSortGroup> = mutableListOf()
    var group: _SemanticsSortGroup? = null
    var depth = 0
    for (edge in edges) {
        if (edge.isLeadingEdge) {
            depth += 1
            group = group ?: _SemanticsSortGroup(
                startOffset = edge.offset,
                textDirection = textDirection
            )
            group.nodes.add(edge.node)
        } else {
            depth -= 1
        }
        if (depth == 0) {
            verticalGroups.add(group!!)
            group = null
        }
    }
    verticalGroups.sort()

    val result: MutableList<SemanticsNode> = mutableListOf()
    for (verticalGroup in verticalGroups) {
        val sortedGroupNodes = verticalGroup.sortedWithinVerticalGroup()
        result.addAll(sortedGroupNodes)
    }
    return result
}

/**
 * In tests use this function to reset the counter used to generate
 * [SemanticsNode.id].
 */
fun debugResetSemanticsIdCounter() {
    SemanticsNode._lastIdentifier = 0
}

//
/**
 * A node that represents some semantic data.
 *
 * The semantics tree is maintained during the semantics phase of the pipeline
 * (i.e., during [PipelineOwner.flushSemantics]), which happens after
 * compositing. The semantics tree is then uploaded into the engine for use
 * by assistive technology.
 */
// TODO(Migration/ryanmentley): This constructor should be private, but the resulting synthetic
// constructor breaks the Kotlin compiler
class SemanticsNode internal constructor(
    /**
     * Uniquely identifies this node in the list of sibling nodes.
     *
     * Keys are used during the construction of the semantics tree. They are not
     * transferred to the engine.
     */
    val key: Key?,
    internal val showOnScreen: VoidCallback?,
    /**
     * The unique identifier for this node.
     *
     * The root node has an id of zero. Other nodes are given a unique id when
     * they are created.
     */
    val id: Int
) : AbstractNode(),
    DiagnosticableTree {

    companion object {
        // TODO(Migration/ryanmentley): Should be private, but is internal to avoid a synthetic accessor
        internal var _lastIdentifier: Int = 0
        // TODO(Migration/ryanmentley): Should be private, but is internal to avoid a synthetic accessor
        internal fun _generateNewId(): Int {
            _lastIdentifier += 1
            return _lastIdentifier
        }

        private val _kEmptyConfig: SemanticsConfiguration = SemanticsConfiguration()

        fun root(
            key: Key? = null,
            showOnScreen: VoidCallback? = null,
            owner: SemanticsOwner
        ): SemanticsNode {
            val node = SemanticsNode(key, showOnScreen, 0)
            node.attach(owner)
            return node
        }
    }

    /**
     * Creates a semantic node.
     *
     * Each semantic node has a unique identifier that is assigned when the node
     * is created.
     */
    constructor(
        key: Key? = null,
        showOnScreen: VoidCallback? = null
    ) : this(key, showOnScreen, _generateNewId())

    // GEOMETRY

    /**
     * The transform from this node's coordinate system to its parent's coordinate system.
     *
     * By default, the transform is null, which represents the identity
     * transformation (i.e., that this node has the same coordinate system as its
     * parent).
     */
    var transform: Matrix4? = null
        set(value) {
            if (!matrixEquals(field, value)) {
                field = if (value?.isIdentity() == true) null else value
                _markDirty()
            }
        }

    /** The bounding box for this node in its coordinate system. */
    var rect: Rect = Rect.zero
        set(value) {
            if (field != value) {
                field = value
                _markDirty()
            }
        }

    /**
     * The semantic clip from an ancestor that was applied to this node.
     *
     * Expressed in the coordinate system of the node. May be null if no clip has
     * been applied.
     *
     * Descendant [SemanticsNode]s that are positioned outside of this rect will
     * be excluded from the semantics tree. Descendant [SemanticsNode]s that are
     * overlapping with this rect, but are outside of [parentPaintClipRect] will
     * be included in the tree, but they will be marked as hidden because they
     * are assumed to be not visible on screen.
     *
     * If this rect is null, all descendant [SemanticsNode]s outside of
     * [parentPaintClipRect] will be excluded from the tree.
     *
     * If this rect is non-null it has to completely enclose
     * [parentPaintClipRect]. If [parentPaintClipRect] is null this property is
     * also null.
     */
    var parentSemanticsClipRect: Rect? = null

    /**
     * The paint clip from an ancestor that was applied to this node.
     *
     * Expressed in the coordinate system of the node. May be null if no clip has
     * been applied.
     *
     * Descendant [SemanticsNode]s that are positioned outside of this rect will
     * either be excluded from the semantics tree (if they have no overlap with
     * [parentSemanticsClipRect]) or they will be included and marked as hidden
     * (if they are overlapping with [parentSemanticsClipRect]).
     *
     * This rect is completely enclosed by [parentSemanticsClipRect].
     *
     * If this rect is null [parentSemanticsClipRect] also has to be null.
     */
    var parentPaintClipRect: Rect? = null

    /**
     * Whether the node is invisible.
     *
     * A node whose [rect] is outside of the bounds of the screen and hence not
     * reachable for users is considered invisible if its semantic information
     * is not merged into a (partially) visible parent as indicated by
     * [isMergedIntoParent].
     *
     * An invisible node can be safely dropped from the semantic tree without
     * loosing semantic information that is relevant for describing the content
     * currently shown on screen.
     */
    val isInvisible: Boolean
        get() = !isMergedIntoParent && rect.isEmpty()

    // MERGING

    /** Whether this node merges its semantic information into an ancestor node. */
    var isMergedIntoParent: Boolean = false
        set(value) {
            if (field == value)
                return
            field = value
            _markDirty()
        }

    /**
     * Whether this node is taking part in a merge of semantic information.
     *
     * This returns true if the node is either merged into an ancestor node or if
     * decedent nodes are merged into this node.
     *
     * See also:
     *
     *  * [isMergedIntoParent]
     *  * [mergeAllDescendantsIntoThisNode]
     */
    val isPartOfNodeMerging
        get() = mergeAllDescendantsIntoThisNode || isMergedIntoParent

    /** Whether this node and all of its descendants should be treated as one logical entity. */
    var mergeAllDescendantsIntoThisNode = _kEmptyConfig.isMergingSemanticsOfDescendants
        private set

    // CHILDREN

    /** Contains the children in inverse hit test order (i.e. paint order). */
    var _children: List<SemanticsNode>? = null

    /**
     * A snapshot of `newChildren` passed to [_replaceChildren] that we keep in
     * debug mode. It supports the assertion that user does not mutate the list
     * of children.
     */
    // TODO(Migration/ryanmentley): Could maybe be non-null?
    var _debugPreviousSnapshot: List<SemanticsNode>? = null

    fun _replaceChildren(newChildren: List<SemanticsNode>) {
        assert(!newChildren.any { child: SemanticsNode -> child == this })
        assert {
            if (newChildren === _children) {
                val mutationErrors = StringBuilder()
                if (newChildren.size != _debugPreviousSnapshot?.size) {
                    mutationErrors.append(
                        "The list\'s length has changed from ${_debugPreviousSnapshot!!.size}" +
                                "to ${newChildren.size}.\n"
                    )
                } else {
                    for (i in 1 until newChildren.size) {
                        if (newChildren[i] !== _debugPreviousSnapshot?.get(i)) {
                            mutationErrors.append(
                                "Child node at position $i was replaced:\n" +
                                        "Previous child: ${newChildren[i]}\n" +
                                        "New child: ${_debugPreviousSnapshot?.get(i)}\n\n"
                            )
                        }
                    }
                }
                if (mutationErrors.isNotEmpty()) {
                    throw FlutterError(
                        "Failed to replace child semantics nodes because the list of " +
                                "`SemanticsNode`s was mutated.\n" +
                                "Instead of mutating the existing list, create a new list " +
                                "containing the desired `SemanticsNode`s.\n" +
                                "Error details:\n" +
                                "$mutationErrors"
                    )
                }
            }
            assert(!newChildren.any {
                node: SemanticsNode -> node.isMergedIntoParent
            } || isPartOfNodeMerging)

            _debugPreviousSnapshot = newChildren.toList()

            var ancestor: SemanticsNode = this
            while (ancestor.parent is SemanticsNode) {
                ancestor = ancestor.parent as SemanticsNode
            }
            assert(!newChildren.any { child: SemanticsNode -> child == ancestor })
            true
        }
        assert {
            val seenChildren: MutableSet<SemanticsNode> = mutableSetOf()
            for (child in newChildren) {
                assert(seenChildren.add(child)); // check for duplicate adds
            }
            true
        }

        // The goal of this function is updating sawChange.
        _children?.let {
            for (child in it)
                child._dead = true
        }
        newChildren.let {
            for (child in it) {
                assert(!child.isInvisible) {
                    "Child $child is invisible and should not be added as a child of $this."
                }
                child._dead = false
            }
        }
        var sawChange = false
        _children?.let {
            for (child in it) {
                if (child._dead) {
                    if (child.parent == this) {
                        // we might have already had our child stolen from us by
                        // another node that is deeper in the tree.
                        dropChild(child)
                    }
                    sawChange = true
                }
            }
        }
        newChildren.let {
            for (child in it) {
                if (child.parent != this) {
                    // we're rebuilding the tree from the bottom up, so it's possible
                    // that our child was, in the last pass, a child of one of our
                    // ancestors. In that case, we drop the child eagerly here.
                    // TODO(ianh): Find a way to assert that the same node didn't
                    // actually appear in the tree in two places.
                    child.parent?.dropChild(child)

                    assert(!child.attached)
                    adoptChild(child)
                    sawChange = true
                }
            }
        }
        if (!sawChange) {
            _children?.let {
                assert(newChildren.size == it.size)
                // Did the order change?
                for (i in 0 until it.size) {
                    if (it[i].id != newChildren[i].id) {
                        sawChange = true
                        break
                    }
                }
            }
        }
        _children = newChildren
        if (sawChange)
            _markDirty()
    }

    /** Whether this node has a non-zero number of children. */
    val hasChildren
        get() = _children?.isNotEmpty() ?: false

    private var _dead = false

    /** The number of children this node has. */
    val childrenCount
        get() = if (hasChildren) _children!!.size else 0

    /**
     * Visits the immediate children of this node.
     *
     * This function calls visitor for each immediate child until visitor returns
     * false. Returns true if all the visitor calls returned true, otherwise
     * returns false.
     */
    fun visitChildren(visitor: SemanticsNodeVisitor) {
        _children?.forEach {
            if (!visitor(it)) {
                return
            }
        }
    }

    /**
     * Visit all the descendants of this node.
     *
     * This function calls visitor for each descendant in a pre-order traversal
     * until visitor returns false. Returns true if all the visitor calls
     * returned true, otherwise returns false.
     */
    fun _visitDescendants(visitor: SemanticsNodeVisitor): Boolean {
        _children?.forEach {
            if (!visitor(it) || !it._visitDescendants(visitor))
                return false
        }
        return true
    }

    // AbstractNode OVERRIDES

    override val owner: SemanticsOwner?
        get() = super.owner as SemanticsOwner?

    // TODO(Migration/ryanmentley): The use of types and overriding here is kind of messy.
    // It requires private backing properties to get around type requirements, which feels like
    // a hack.
    override val parent: SemanticsNode?
        get() = super.parent as SemanticsNode?

    override fun redepthChildren() {
        _children?.forEach(::redepthChild)
    }

    // TODO(Migration/ryanmentley): Removed covariant
    override fun attach(owner: Any) {
        owner as SemanticsOwner

        super.attach(owner)
        assert(!owner._nodes.containsKey(id))
        owner._nodes[id] = this
        owner._detachedNodes.remove(this)
        if (_dirty) {
            _dirty = false
            _markDirty()
        }
        _children?.let {
            for (child in it) {
                child.attach(owner)
            }
        }
    }

    // TODO(Migration/ryanmentley): Should we make this API idempotent so that it works if detached
    // more than once?
    override fun detach() {
        owner!!.let {
            assert(it._nodes.containsKey(id))
            assert(!it._detachedNodes.contains(this))
            it._nodes.remove(id)
            it._detachedNodes.add(this)
        }
        super.detach()
        assert(owner == null)
        _children?.let {
            for (child in it) {
                // The list of children may be stale and may contain nodes that have
                // been assigned to a different parent.
                if (child.parent == this)
                    child.detach()
            }
        }
        // The other side will have forgotten this node if we ever send
        // it again, so make sure to mark it dirty so that it'll get
        // sent if it is resurrected.
        _markDirty()
    }

// DIRTY MANAGEMENT

    internal var _dirty: Boolean = false

    fun _markDirty() {
        if (_dirty)
            return
        _dirty = true
        if (attached) {
            owner!!.let {
                assert(!it._detachedNodes.contains(this))
                it._dirtyNodes.add(this)
            }
        }
    }

    fun _isDifferentFromCurrentSemanticAnnotation(config: SemanticsConfiguration): Boolean {
        return label != config.label ||
                hint != config.hint ||
                decreasedValue != config.decreasedValue ||
                value != config.value ||
                increasedValue != config.increasedValue ||
                _flags != config._flags ||
                textDirection != config.textDirection ||
                sortKey != config.sortKey ||
                textSelection != config.textSelection ||
                scrollPosition != config.scrollPosition ||
                scrollExtentMax != config.scrollExtentMax ||
                scrollExtentMin != config.scrollExtentMin ||
                _actionsAsBits != config._actionsAsBits ||
                mergeAllDescendantsIntoThisNode != config.isMergingSemanticsOfDescendants
    }

    // TAGS, LABELS, ACTIONS

    var _actions: Map<SemanticsAction, _SemanticsActionHandler> = _kEmptyConfig._actions

    var _actionsAsBits = _kEmptyConfig._actionsAsBits

    /**
     * The [SemanticsTag]s this node is tagged with.
     *
     * Tags are used during the construction of the semantics tree. They are not
     * transferred to the engine.
     */
    var tags: Set<SemanticsTag>? = null

    /** Whether this node is tagged with `tag`. */
    fun isTagged(tag: SemanticsTag) = tags?.contains(tag) == true

    private var _flags: Int = _kEmptyConfig._flags

    private fun _hasFlag(flag: SemanticsFlag) = _flags and flag.index != 0

    /**
     * A textual description of this node.
     *
     * The reading direction is given by [textDirection].
     */
    var label: String = _kEmptyConfig.label
        private set

    /**
     * A textual description for the current value of the node.
     *
     * The reading direction is given by [textDirection].
     */
    var value: String = _kEmptyConfig.value
        private set

    /**
     * The value that [value] will have after a [SemanticsAction.decrease] action
     * has been performed.
     *
     * This property is only valid if the [SemanticsAction.decrease] action is
     * available on this node.
     *
     * The reading direction is given by [textDirection].
     */
    var decreasedValue: String = _kEmptyConfig.decreasedValue
        private set

    /**
     * The value that [value] will have after a [SemanticsAction.increase] action
     * has been performed.
     *
     * This property is only valid if the [SemanticsAction.increase] action is
     * available on this node.
     *
     * The reading direction is given by [textDirection].
     */
    var increasedValue: String = _kEmptyConfig.increasedValue
        private set

    // / A brief description of the result of performing an action on this node.
    // /
    // / The reading direction is given by [textDirection].
    var hint: String = _kEmptyConfig.hint
        private set

    /**
     * The reading direction for [label], [value], [hint], [increasedValue], and
     * [decreasedValue].
     */
    var textDirection: TextDirection? = _kEmptyConfig.textDirection
        private set

    /**
     * Determines the position of this node among its siblings in the traversal
     * sort order.
     *
     * This is used to describe the order in which the semantic node should be
     * traversed by the accessibility services on the platform (e.g. VoiceOver
     * on iOS and TalkBack on Android).
     */
    var sortKey: SemanticsSortKey? = null
        private set

    /**
     * The currently selected text (or the position of the cursor) within [value]
     * if this node represents a text field.
     */
    var textSelection: TextSelection? = null
        private set

    /**
     * Indicates the current scrolling position in logical pixels if the node is
     * scrollable.
     *
     * The properties [scrollExtentMin] and [scrollExtentMax] indicate the valid
     * in-range values for this property. The value for [scrollPosition] may
     * (temporarily) be outside that range, e.g. during an overscroll.
     *
     * See also:
     *
     *  * [ScrollPosition.pixels], from where this value is usually taken.
     */
    var scrollPosition: Float? = null
        private set

    /**
     * Indicates the maximum in-range value for [scrollPosition] if the node is
     * scrollable.
     *
     * This value may be infinity if the scroll is unbound.
     *
     * See also:
     *
     *  * [ScrollPosition.maxScrollExtent], from where this value is usually taken.
     */
    var scrollExtentMax: Float? = null
        private set

    /**
     * Indicates the minimum in-range value for [scrollPosition] if the node is
     * scrollable.
     *
     * This value may be infinity if the scroll is unbound.
     *
     * See also:
     *
     *  * [ScrollPosition.minScrollExtent] from where this value is usually taken.
     */
    var scrollExtentMin: Float? = null
        private set

    internal fun _canPerformAction(action: SemanticsAction) = _actions.containsKey(action)

    /**
     * Reconfigures the properties of this object to describe the configuration
     * provided in the `config` argument and the children listed in the
     * `childrenInInversePaintOrder` argument.
     *
     * The arguments may be null; this represents an empty configuration (all
     * values at their defaults, no children).
     *
     * No reference is kept to the [SemanticsConfiguration] object, but the child
     * list is used as-is and should therefore not be changed after this call.
     */
    fun updateWith(
        config: SemanticsConfiguration?,
        childrenInInversePaintOrder: List<SemanticsNode>?
    ) {
        val sourceConfig = config ?: _kEmptyConfig
        if (_isDifferentFromCurrentSemanticAnnotation(sourceConfig))
            _markDirty()

        label = sourceConfig.label
        decreasedValue = sourceConfig.decreasedValue
        value = sourceConfig.value
        increasedValue = sourceConfig.increasedValue
        hint = sourceConfig.hint
        _flags = sourceConfig._flags
        textDirection = sourceConfig.textDirection
        sortKey = sourceConfig.sortKey
        _actions = sourceConfig._actions.toMap()
        _actionsAsBits = sourceConfig._actionsAsBits
        textSelection = sourceConfig.textSelection
        scrollPosition = sourceConfig.scrollPosition
        scrollExtentMax = sourceConfig.scrollExtentMax
        scrollExtentMin = sourceConfig.scrollExtentMin
        mergeAllDescendantsIntoThisNode = sourceConfig.isMergingSemanticsOfDescendants
        _replaceChildren(childrenInInversePaintOrder ?: listOf())

        assert(
            !_canPerformAction(
                SemanticsAction.increase
            ) || (value == "") == (increasedValue == "")
        ) {
            "A SemanticsNode with action \"increase\" needs to be annotated with either both " +
                    "\"value\" and \"increasedValue\" or neither"
        }
        assert(
            !_canPerformAction(
                SemanticsAction.decrease
            ) || (value == "") == (decreasedValue == "")
        ) {
            "A SemanticsNode with action \"increase\" needs to be annotated with either both " +
                    "\"value\" and \"decreasedValue\" or neither"
        }
    }

    /**
     * Returns a summary of the semantics for this node.
     *
     * If this node has [mergeAllDescendantsIntoThisNode], then the returned data
     * includes the information from this node's descendants. Otherwise, the
     * returned data matches the data on this node.
     */
    fun getSemanticsData(): SemanticsData {
        var flags = _flags
        var actions = _actionsAsBits
        var label = label
        var hint = hint
        var value = value
        var increasedValue = increasedValue
        var decreasedValue = decreasedValue
        var textDirection = textDirection
        var mergedTags: MutableSet<SemanticsTag> = tags?.toMutableSet() ?: mutableSetOf()
        var textSelection = textSelection
        var scrollPosition = scrollPosition
        var scrollExtentMax = scrollExtentMax
        var scrollExtentMin = scrollExtentMin

        if (mergeAllDescendantsIntoThisNode) {
            _visitDescendants { node: SemanticsNode ->
                assert(node.isMergedIntoParent)
                flags = flags or node._flags
                actions = actions or node._actionsAsBits
                textDirection = textDirection ?: node.textDirection
                textSelection = textSelection ?: node.textSelection
                scrollPosition = scrollPosition ?: node.scrollPosition
                scrollExtentMax = scrollExtentMax ?: node.scrollExtentMax
                scrollExtentMin = scrollExtentMin ?: node.scrollExtentMin
                if (value.isEmpty()) {
                    value = node.value
                }
                if (increasedValue.isEmpty()) {
                    increasedValue = node.increasedValue
                }
                if (decreasedValue.isEmpty()) {
                    decreasedValue = node.decreasedValue
                }
                node.tags?.let {
                    val localMergedTags = mergedTags
                    localMergedTags.addAll(it)
                }

                label = _concatStrings(
                    thisString = label,
                    thisTextDirection = textDirection,
                    otherString = node.label,
                    otherTextDirection = node.textDirection
                )
                hint = _concatStrings(
                    thisString = hint,
                    thisTextDirection = textDirection,
                    otherString = node.hint,
                    otherTextDirection = node.textDirection
                )
                return@_visitDescendants true
            }
        }

        return SemanticsData(
            flags = flags,
            actions = actions,
            label = label,
            value = value,
            increasedValue = increasedValue,
            decreasedValue = decreasedValue,
            hint = hint,
            textDirection = textDirection,
            rect = rect,
            transform = transform,
            tags = mergedTags,
            textSelection = textSelection,
            scrollPosition = scrollPosition,
            scrollExtentMax = scrollExtentMax,
            scrollExtentMin = scrollExtentMin
        )
    }

    // TODO(Migration/ryanmentley): remove synthetic accessors?
    // _kEmptyChildList, _kIdentityTransform, _kEmptyCustomSemanticsActionsList
    @SuppressLint("SyntheticAccessor")
    fun _addToUpdate(
        builder: SemanticsUpdateBuilder,
        customSemanticsActionIdsUpdate: MutableSet<Int>
    ) {
        assert(_dirty)
        val data: SemanticsData = getSemanticsData()
        val childrenInTraversalOrder: Int32List
        val childrenInHitTestOrder: Int32List
        if (!hasChildren || mergeAllDescendantsIntoThisNode) {
            childrenInTraversalOrder = _kEmptyChildList
            childrenInHitTestOrder = _kEmptyChildList
        } else {
            val childCount = _children!!.size
            val sortedChildren = _childrenInTraversalOrder()
            childrenInTraversalOrder = Int32List(childCount)
            for (i in 0 until childCount) {
                childrenInTraversalOrder[i] = sortedChildren[i].id
            }
            // _children is sorted in paint order, so we invert it to get the hit test
            // order.
            childrenInHitTestOrder = Int32List(childCount)
            for (i in childCount - 1 downTo 0) {
                childrenInHitTestOrder[i] = _children!![childCount - i - 1].id
            }
        }
        builder.updateNode(
            id = id,
            flags = data.flags,
            actions = data.actions,
            rect = data.rect,
            label = data.label,
            value = data.value,
            decreasedValue = data.decreasedValue,
            increasedValue = data.increasedValue,
            hint = data.hint,
            textDirection = data.textDirection,
            textSelectionBase = if (data.textSelection != null) {
                data.textSelection.baseOffset
            } else {
                -1
            },
            textSelectionExtent = if (data.textSelection != null) {
                data.textSelection.extentOffset
            } else {
                -1
            },
            scrollPosition = data.scrollPosition ?: Float.NaN,
            scrollExtentMax = data.scrollExtentMax ?: Float.NaN,
            scrollExtentMin = data.scrollExtentMin ?: Float.NaN,
            transform = data.transform?.m4storage?.toFloatArray() ?: _kIdentityTransform,
            childrenInTraversalOrder = childrenInTraversalOrder,
            childrenInHitTestOrder = childrenInHitTestOrder
        )
        _dirty = false
    }

    // TODO(Migration/ryanmentley): remove synthetic accessors?
    // _childrenInDefaultOrder
    @SuppressLint("SyntheticAccessor")
    /** Builds a new list made of [_children] sorted in semantic traversal order. */
    fun _childrenInTraversalOrder(): List<SemanticsNode> {
        var inheritedTextDirection = textDirection
        var ancestor = parent
        while (inheritedTextDirection == null && ancestor != null) {
            inheritedTextDirection = ancestor.textDirection
            ancestor = ancestor.parent
        }

        val childrenInDefaultOrder: List<SemanticsNode>
        if (inheritedTextDirection != null) {
            childrenInDefaultOrder = _childrenInDefaultOrder(_children!!, inheritedTextDirection)
        } else {
            // In the absence of text direction default to paint order.
            childrenInDefaultOrder = _children!!
        }

        // List.sort does not guarantee stable sort order. Therefore, children are
        // first partitioned into groups that have compatible sort keys, i.e. keys
        // in the same group can be compared to each other. These groups stay in
        // the same place. Only children within the same group are sorted.
        val everythingSorted: MutableList<_TraversalSortNode> = mutableListOf()
        val sortNodes: MutableList<_TraversalSortNode> = mutableListOf()
        var lastSortKey: SemanticsSortKey? = null
        for (position in 0 until childrenInDefaultOrder.size) {
            val child: SemanticsNode = childrenInDefaultOrder[position]
            val sortKey: SemanticsSortKey? = child.sortKey
            lastSortKey = if (position > 0) {
                childrenInDefaultOrder[position - 1].sortKey
            } else {
                null
            }
            val isCompatibleWithPreviousSortKey: Boolean = position == 0 ||
                    sortKey?.runtimeType() == lastSortKey?.runtimeType() &&
                    (sortKey == null || sortKey.name == lastSortKey!!.name)
            if (!isCompatibleWithPreviousSortKey && sortNodes.isNotEmpty()) {
                // Do not sort groups with null sort keys. List.sort does not guarantee
                // a stable sort order.
                if (lastSortKey != null) {
                    sortNodes.sort()
                }
                everythingSorted.addAll(sortNodes)
                sortNodes.clear()
            }

            sortNodes.add(
                _TraversalSortNode(
                    node = child,
                    sortKey = sortKey,
                    position = position
                )
            )
        }

        // Do not sort groups with null sort keys. List.sort does not guarantee
        // a stable sort order.
        if (lastSortKey != null) {
            sortNodes.sort()
        }
        everythingSorted.addAll(sortNodes)

        return everythingSorted
            .map { sortNode: _TraversalSortNode -> sortNode.node }
            .toList()
    }

    /**
     * Sends a [SemanticsEvent] associated with this [SemanticsNode].
     *
     * Semantics events should be sent to inform interested parties (like
     * the accessibility system of the operating system) about changes to the UI.
     *
     * For example, if this semantics node represents a scrollable list, a
     * [ScrollCompletedSemanticsEvent] should be sent after a scroll action is completed.
     * That way, the operating system can give additional feedback to the user
     * about the state of the UI (e.g. on Android a ping sound is played to
     * indicate a successful scroll in accessibility mode).
     */
    fun sendEvent(event: SemanticsEvent) {
        TODO("needs channels")
//    if (!attached)
//      return;
//    SystemChannels.accessibility.send(event.toMap(nodeId = id));
    }

    override fun toStringShort() = "${runtimeType()}#$id"

    override fun debugFillProperties(properties: DiagnosticPropertiesBuilder) {
        var hideOwner = true
        if (_dirty) {
            val inDirtyNodes = owner?._dirtyNodes?.contains(this) == true
            properties.add(
                FlagProperty(
                    "inDirtyNodes",
                    value = inDirtyNodes,
                    ifTrue = "dirty",
                    ifFalse = "STALE"
                )
            )
            hideOwner = inDirtyNodes
        }
        properties.add(
            DiagnosticsProperty.create(
                "owner",
                owner,
                level = if (hideOwner) DiagnosticLevel.hidden else DiagnosticLevel.info
            )
        )
        properties.add(
            FlagProperty(
                "isMergedIntoParent",
                value = isMergedIntoParent,
                ifTrue = "merged up ⬆️"
            )
        )
        properties.add(
            FlagProperty(
                "mergeAllDescendantsIntoThisNode",
                value = mergeAllDescendantsIntoThisNode,
                ifTrue = "merge boundary ⛔️"
            )
        )
        val offset = transform?.getAsTranslation()
        if (offset != null) {
            properties.add(
                DiagnosticsProperty.create(
                    "rect",
                    rect.shift(offset),
                    showName = false
                )
            )
        } else {
            val scale = transform?.getAsScale()
            val description = when {
                scale != null -> "$rect scaled by ${scale.toStringAsFixed(1)}x"

                transform?.isIdentity() == false -> {
                    val matrix: String = transform
                        .toString()
                        .split("\n")
                        .take(4)
                        .map { line: String -> line.substring(4) }
                        .joinToString("; ")
                    "$rect with transform [$matrix]"
                }
                else -> null
            }
            properties.add(
                DiagnosticsProperty.create(
                    "rect",
                    rect,
                    description = description,
                    showName = false
                )
            )
        }
        val actions: List<String> = _actions.keys.map { action: SemanticsAction ->
            describeEnum(action)
        }.sorted()
        properties.add(IterableProperty("actions", actions, ifEmpty = null))
        val flags: List<String> = SemanticsFlag.values.values
            .filter { flag: SemanticsFlag -> _hasFlag(flag) }
            .map { flag: SemanticsFlag -> flag.toString().substring("SemanticsFlag.".length) }
            .toList()
        properties.add(IterableProperty<String>("flags", flags, ifEmpty = null))
        properties.add(
            FlagProperty("isInvisible", value = isInvisible, ifTrue = "invisible")
        )
        properties.add(
            FlagProperty(
                "isHidden", value = _hasFlag(SemanticsFlag.isHidden),
                ifTrue = "HIDDEN"
            )
        )
        properties.add(StringProperty("label", label, defaultValue = ""))
        properties.add(StringProperty("value", value, defaultValue = ""))
        properties.add(
            StringProperty("increasedValue", increasedValue, defaultValue = "")
        )
        properties.add(
            StringProperty("decreasedValue", decreasedValue, defaultValue = "")
        )
        properties.add(StringProperty("hint", hint, defaultValue = ""))
        properties.add(
            EnumProperty<TextDirection>(
                "textDirection", textDirection,
                defaultValue = null
            )
        )
        properties.add(
            DiagnosticsProperty.create(
                "sortKey", sortKey,
                defaultValue = null
            )
        )
        textSelection?.let {
            if (it.isValid) {
                properties.add(
                    MessageProperty(
                        "text selection",
                        "[${it.start}, ${it.end}]"
                    )
                )
            }
        }
        properties.add(
            FloatProperty.create("scrollExtentMin", scrollExtentMin, defaultValue = null)
        )
        properties.add(
            FloatProperty.create("scrollPosition", scrollPosition, defaultValue = null)
        )
        properties.add(
            FloatProperty.create("scrollExtentMax", scrollExtentMax, defaultValue = null)
        )
    }

    /**
     * Returns a string representation of this node and its descendants.
     *
     * The order in which the children of the [SemanticsNode] will be printed is
     * controlled by the [childOrder] parameter.
     */
    override fun toStringDeep(
        prefixLineOne: String,
        prefixOtherLines: String,
        minLevel: DiagnosticLevel
    ): String {
        return toStringDeep(
            prefixLineOne,
            prefixOtherLines,
            minLevel,
            DebugSemanticsDumpOrder.traversalOrder
        )
    }

    fun toStringDeep(
        prefixLineOne: String = "",
        prefixOtherLines: String = "",
        minLevel: DiagnosticLevel = DiagnosticLevel.debug,
        childOrder: DebugSemanticsDumpOrder = DebugSemanticsDumpOrder.traversalOrder
    ): String {
        return toDiagnosticsNode(childOrder = childOrder).toStringDeep(
            prefixLineOne = prefixLineOne,
            prefixOtherLines = prefixOtherLines,
            minLevel = minLevel
        )
    }

    override fun toDiagnosticsNode(
        name: String?,
        style: DiagnosticsTreeStyle?
    ): DiagnosticsNode {
        // NOTE(ryanmentley): Migrated from overridden default param
        val defaultedStyle = style ?: DiagnosticsTreeStyle.sparse
        return toDiagnosticsNode(
            name, defaultedStyle,
            DebugSemanticsDumpOrder.traversalOrder
        )
    }

    fun toDiagnosticsNode(
        name: String? = null,
        style: DiagnosticsTreeStyle = DiagnosticsTreeStyle.sparse,
        childOrder: DebugSemanticsDumpOrder
    ): DiagnosticsNode {
        return _SemanticsDiagnosticableNode(
            name = name,
            value = this,
            style = style,
            childOrder = childOrder
        )
    }

    override fun toString() = toStringDiagnostic()

    override fun debugDescribeChildren(): List<DiagnosticsNode> {
        return debugDescribeChildren(DebugSemanticsDumpOrder.inverseHitTest)
    }

    fun debugDescribeChildren(
        childOrder: DebugSemanticsDumpOrder
    ): List<DiagnosticsNode> {
        return debugListChildrenInOrder(childOrder)
            .map { node: SemanticsNode -> node.toDiagnosticsNode(childOrder = childOrder) }
            .toList()
    }

    /** Returns the list of direct children of this node in the specified order. */
    fun debugListChildrenInOrder(childOrder: DebugSemanticsDumpOrder): List<SemanticsNode> {
        val localChildren = _children ?: return listOf()

        return when (childOrder) {
            DebugSemanticsDumpOrder.inverseHitTest ->
                localChildren
            DebugSemanticsDumpOrder.traversalOrder ->
                _childrenInTraversalOrder()
        }
    }
}
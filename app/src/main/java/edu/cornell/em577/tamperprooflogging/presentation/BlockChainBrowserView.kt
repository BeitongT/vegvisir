package edu.cornell.em577.tamperprooflogging.presentation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.util.AttributeSet
import android.view.View
import edu.cornell.em577.tamperprooflogging.data.model.SignedBlock
import edu.cornell.em577.tamperprooflogging.data.source.BlockChainRepository
import java.util.*
import kotlin.math.pow

class BlockChainBrowserView(context: Context, attributeSet: AttributeSet) :
    View(context, attributeSet) {

    /** A node in the directed acyclic graph of blocks that is fixed on a canvas */
    private data class CanvasBlockNode(
        val canvasBlock: CanvasBlock,
        val parents: List<CanvasBlockNode>
    ) {
        data class CanvasBlock(val signedBlock: SignedBlock, val point: Point)
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Path()
    private val leftArrowEdge = Path()
    private val rightArrowEdge = Path()
    private var frontierCanvasBlockNode: CanvasBlockNode =
        canvasBlocksToCanvasBlockNodes(blocksToCanvasBlocks())

    companion object {
        private const val NODE_RADIUS = 15
        private const val EDGE_WIDTH = 2
        private const val ARROW_LENGTH = 10
        private const val ARROW_DEGREE_OFFSET = 30
        private const val INTRA_LAYER_DISTANCE = 50
        private const val INTER_LAYER_DISTANCE = 100
        private const val BASE_X = 100
        private const val BASE_Y = 250
    }

    /** Maps blocks in the repository to canvas blocks */
    private fun blocksToCanvasBlocks(): HashMap<String, CanvasBlockNode.CanvasBlock> {
        val blockRepository = BlockChainRepository.getInstance(Pair(context, resources))
        val rootBlock = blockRepository.getRootBlock()
        val canvasBlockByCryptoHash = HashMap<String, CanvasBlockNode.CanvasBlock>()
        var currentLayer = listOf(rootBlock)
        var currentLayerNum = 0
        while (currentLayer.isNotEmpty()) {
            (0 until currentLayer.size).forEach({
                canvasBlockByCryptoHash[currentLayer[it].cryptoHash] =
                        CanvasBlockNode.CanvasBlock(
                            currentLayer[it],
                            Point(
                                BASE_X + currentLayerNum * INTER_LAYER_DISTANCE,
                                BASE_Y + (it - currentLayer.size / 2) * INTRA_LAYER_DISTANCE
                            )
                        )
            })
            val currentLayerParentHashes = LinkedHashSet<String>()
            for (block in currentLayer) {
                currentLayerParentHashes.addAll(block.unsignedBlock.parentHashes)
            }
            currentLayer = blockRepository.getBlocks(currentLayerParentHashes)
            currentLayerNum += 1
        }
        return canvasBlockByCryptoHash
    }

    /** Maps specified canvas blocks to canvas signedBlock nodes */
    private fun canvasBlocksToCanvasBlockNodes(
        canvasBlockByCryptoHash: HashMap<String, CanvasBlockNode.CanvasBlock>
    ): CanvasBlockNode {
        val blockRepository = BlockChainRepository.getInstance(Pair(context, resources))
        val frontierBlock = blockRepository.getRootBlock()
        val visitedCanvasBlockNodeByCryptoHash = HashMap<String, CanvasBlockNode>()
        val stack = ArrayDeque<CanvasBlockNode.CanvasBlock>(
            listOf(
                canvasBlockByCryptoHash[frontierBlock.cryptoHash]
            )
        )

        while (stack.isNotEmpty()) {
            val canvasRootBlock = stack.pop()
            val canvasBlocksToVisit = ArrayList<CanvasBlockNode.CanvasBlock>()
            for (parentHash in canvasRootBlock.signedBlock.unsignedBlock.parentHashes) {
                if (parentHash !in visitedCanvasBlockNodeByCryptoHash) {
                    canvasBlocksToVisit.add(canvasBlockByCryptoHash[parentHash]!!)
                }
            }
            if (canvasBlocksToVisit.isEmpty()) {
                val rootNode =
                    CanvasBlockNode(
                        canvasRootBlock,
                        canvasRootBlock.signedBlock.unsignedBlock.parentHashes.map {
                            visitedCanvasBlockNodeByCryptoHash[it]!!
                        })
                visitedCanvasBlockNodeByCryptoHash[canvasRootBlock.signedBlock.cryptoHash] =
                        rootNode

                if (stack.isEmpty()) {
                    return rootNode
                }
            } else {
                stack.push(canvasRootBlock)
                canvasBlocksToVisit.forEach({ stack.push(it) })
            }
        }
        throw RuntimeException("Cycle in blockchain found!")
    }

    /**
     * Updates the canvas frontier node. Assumes that the blockchain provided is consistent and
     * forms an acyclic directed graph
     */
    private fun updateFrontierCanvasBlockNode() {
        frontierCanvasBlockNode = canvasBlocksToCanvasBlockNodes(blocksToCanvasBlocks())
    }

    override fun onDraw(canvas: Canvas) {
        updateFrontierCanvasBlockNode()
        drawGraph(canvas)
    }

    private fun drawGraph(canvas: Canvas) {
        drawNode(canvas, frontierCanvasBlockNode.canvasBlock.point)
        val queue = ArrayDeque(listOf(frontierCanvasBlockNode))
        val cryptoHashesOfVisitedBlocks = HashSet(listOf(frontierCanvasBlockNode.canvasBlock.signedBlock.cryptoHash))
        while (queue.isNotEmpty()) {
            val currentNode = queue.pop()
            for (parentNode in currentNode.parents) {
                if (parentNode.canvasBlock.signedBlock.cryptoHash !in cryptoHashesOfVisitedBlocks) {
                    drawNode(canvas, parentNode.canvasBlock.point)
                    cryptoHashesOfVisitedBlocks.add(parentNode.canvasBlock.signedBlock.cryptoHash)
                    queue.addLast(parentNode)
                }
                drawEdge(canvas, currentNode.canvasBlock.point, parentNode.canvasBlock.point)
            }
        }
    }

    private fun drawNode(canvas: Canvas, point: Point) {
        paint.reset()
        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK
        canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), NODE_RADIUS.toFloat(), paint)
    }

    private fun getDistance(src: Point, dest: Point): Double {
        return Math.sqrt((src.x - dest.x).toDouble().pow(2) +
                (src.y - dest.y).toDouble().pow(2))
    }

    private fun drawEdge(canvas: Canvas, src: Point, dest: Point) {
        paint.reset()
        edge.moveTo(src.x.toFloat(), src.y.toFloat())
        edge.lineTo(dest.x.toFloat(), dest.y.toFloat())

        val distance = getDistance(src, dest)
        val xCoord = src.x + (dest.x.toFloat() - src.x) * (distance - NODE_RADIUS) / distance
        val yCoord = src.y + (dest.y.toFloat() - src.y) * (distance - NODE_RADIUS) / distance

        val arrowRadianOffset = Math.PI * (ARROW_DEGREE_OFFSET / 180.0)
        val positiveOffsetCos = Math.cos(arrowRadianOffset)
        val positiveOffsetSin = Math.sin(arrowRadianOffset)
        val negativeOffsetCos = Math.cos(-arrowRadianOffset)
        val negativeOffsetSin = Math.sin(-arrowRadianOffset)
        val arrowXCoord = (src.x.toFloat() - dest.x) * ARROW_LENGTH / distance
        val arrowYCoord = (src.y.toFloat() - dest.y) * ARROW_LENGTH / distance

        val positiveOffsetArrowXCoord =
            positiveOffsetCos * arrowXCoord - positiveOffsetSin * arrowYCoord
        val positiveOffsetArrowYCoord =
            positiveOffsetSin * arrowXCoord + positiveOffsetCos * arrowYCoord
        val negativeOffsetArrowXCoord =
            negativeOffsetCos * arrowXCoord - negativeOffsetSin * arrowYCoord
        val negativeOffsetArrowYCoord =
            negativeOffsetSin * arrowXCoord + negativeOffsetCos * arrowYCoord

        leftArrowEdge.moveTo(xCoord.toFloat(), yCoord.toFloat())
        leftArrowEdge.lineTo(
            (xCoord.toFloat() + positiveOffsetArrowXCoord).toFloat(),
            (yCoord + positiveOffsetArrowYCoord).toFloat()
        )

        rightArrowEdge.moveTo(xCoord.toFloat(), yCoord.toFloat())
        rightArrowEdge.lineTo(
            (xCoord.toFloat() + negativeOffsetArrowXCoord).toFloat(),
            (yCoord + negativeOffsetArrowYCoord).toFloat()
        )

        paint.strokeWidth = EDGE_WIDTH.toFloat()
        paint.style = Paint.Style.STROKE
        paint.color = Color.BLACK
        canvas.drawPath(edge, paint)
        canvas.drawPath(leftArrowEdge, paint)
        canvas.drawPath(rightArrowEdge, paint)
    }
}
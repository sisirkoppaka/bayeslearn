package io.github.mandar2812.dynaml.graphutils

import com.tinkerpop.frames.{InVertex, OutVertex, EdgeFrame}

/**
 * Defines the [[EdgeFrame]] for the
 * edges going out from the parameter
 * node.
 */
trait ParamEdge extends EdgeFrame {
  @OutVertex
  def getParameter(): Parameter

  @InVertex
  def getLabel(): Label
}

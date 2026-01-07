package io.leavesfly.jimi.plugin.ui

import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent
import javax.swing.Timer
import kotlin.math.sin

/**
 * 动画图标组件
 * 支持加载动画、成功动画、错误动画等
 */
class AnimatedIcon(private val type: AnimationType) : JComponent() {
    
    enum class AnimationType {
        LOADING,    // 旋转加载
        SUCCESS,    // 成功打勾
        ERROR,      // 错误叉号
        THINKING    // 思考动画
    }
    
    private var angle = 0.0
    private var progress = 0.0
    private val timer: Timer
    
    init {
        preferredSize = java.awt.Dimension(24, 24)
        
        timer = Timer(50) { e -> // 20 FPS
            when (type) {
                AnimationType.LOADING -> {
                    angle += 15.0
                    if (angle >= 360.0) angle = 0.0
                }
                AnimationType.SUCCESS, AnimationType.ERROR -> {
                    progress += 0.1
                    if (progress >= 1.0) {
                        progress = 1.0
                        (e.source as Timer).stop()
                    }
                }
                AnimationType.THINKING -> {
                    angle += 5.0
                    if (angle >= 360.0) angle = 0.0
                }
            }
            repaint()
        }
    }
    
    fun startAnimation() {
        angle = 0.0
        progress = 0.0
        timer.start()
    }
    
    fun stopAnimation() {
        timer.stop()
    }
    
    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2d = g as Graphics2D
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        
        when (type) {
            AnimationType.LOADING -> paintLoading(g2d)
            AnimationType.SUCCESS -> paintSuccess(g2d)
            AnimationType.ERROR -> paintError(g2d)
            AnimationType.THINKING -> paintThinking(g2d)
        }
    }
    
    private fun paintLoading(g2d: Graphics2D) {
        val centerX = width / 2
        val centerY = height / 2
        val radius = 8
        
        g2d.color = java.awt.Color(33, 150, 243)
        g2d.rotate(Math.toRadians(angle), centerX.toDouble(), centerY.toDouble())
        
        for (i in 0 until 8) {
            val alpha = (255 * (1.0 - i / 8.0)).toInt()
            g2d.color = java.awt.Color(33, 150, 243, alpha)
            val x = centerX + (radius * kotlin.math.cos(Math.toRadians(i * 45.0))).toInt()
            val y = centerY + (radius * kotlin.math.sin(Math.toRadians(i * 45.0))).toInt()
            g2d.fillOval(x - 2, y - 2, 4, 4)
        }
    }
    
    private fun paintSuccess(g2d: Graphics2D) {
        g2d.color = java.awt.Color(76, 175, 80)
        g2d.stroke = java.awt.BasicStroke(3f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
        
        val maxLen = 12
        val len = (maxLen * progress).toInt()
        
        // 绘制打勾
        g2d.drawLine(6, 12, 6 + (len / 2).coerceAtMost(4), 12 + (len / 2).coerceAtMost(4))
        if (len > 4) {
            g2d.drawLine(10, 16, 10 + (len - 4).coerceAtMost(8), 16 - (len - 4).coerceAtMost(8))
        }
    }
    
    private fun paintError(g2d: Graphics2D) {
        g2d.color = java.awt.Color(244, 67, 54)
        g2d.stroke = java.awt.BasicStroke(3f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
        
        val maxLen = 12
        val len = (maxLen * progress).toInt()
        
        // 绘制叉号
        g2d.drawLine(6, 6, 6 + len, 6 + len)
        g2d.drawLine(18 - len, 6, 18, 6 + len)
    }
    
    private fun paintThinking(g2d: Graphics2D) {
        val centerX = width / 2
        val centerY = height / 2
        
        // 三个跳动的点
        for (i in 0 until 3) {
            val offset = (sin(Math.toRadians(angle + i * 120.0)) * 3).toInt()
            g2d.color = java.awt.Color(96, 125, 139)
            g2d.fillOval(centerX - 8 + i * 6, centerY - 2 + offset, 4, 4)
        }
    }
}

/**
 * 图标集合
 */
object Icons {
    const val ROBOT = "🤖"
    const val USER = "👤"
    const val SUCCESS = "✓"
    const val ERROR = "✗"
    const val WARNING = "⚠"
    const val INFO = "ℹ"
    const val LOADING = "⏳"
    const val THINKING = "💭"
    const val CODE = "💻"
    const val TOOL = "🔧"
    const val SEARCH = "🔍"
    const val FILE = "📄"
    const val FOLDER = "📁"
    const val LIGHTNING = "⚡"
    const val ROCKET = "🚀"
    const val SPARKLES = "✨"
}

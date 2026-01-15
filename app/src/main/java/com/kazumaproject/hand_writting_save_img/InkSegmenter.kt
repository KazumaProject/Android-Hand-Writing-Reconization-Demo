package com.kazumaproject.hand_writting_save_img

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 1枚の白背景＋黒インク画像から「文字っぽい領域」を複数bboxとして抽出する。
 *
 * 前提:
 * - 背景は白
 * - インクは黒〜濃いグレー
 *
 * 戦略:
 * 1) 二値化(inkMask)
 * 2) 連結成分でbbox取得
 * 3) 近接クラスタリング（文字内の分離パーツを結合: て/き/はね/濁点など）
 * 4) bboxが広すぎる場合は縦投影の谷で分割（複数文字がくっついたケースを救う）
 * 5) 左→右にソートして返す
 */
object InkSegmenter {

    data class SegConfig(
        val inkThresh: Int = 245,          // gray < inkThresh をインク扱い
        val minArea: Int = 25,             // ノイズ除去
        val clusterPadPx: Int = 10,        // 近い成分を同一文字として結合する拡張幅
        val maxChars: Int = 16,            // 安全上限
        val wideBoxSplitRatio: Float = 1.35f, // bbox.width / bbox.height がこれ以上なら「複数文字疑い」
        val projectionMinGapPx: Int = 4,   // 分割の最小隙間
        val projectionWindowPx: Int = 3    // 投影の平滑化
    )

    fun segment(src: Bitmap, cfg: SegConfig = SegConfig()): List<Rect> {
        val w = src.width
        val h = src.height
        if (w <= 1 || h <= 1) return emptyList()

        val gray = IntArray(w * h)
        val ink = BooleanArray(w * h)

        src.getPixels(gray, 0, w, 0, 0, w, h)

        // 二値化
        for (y in 0 until h) {
            val off = y * w
            for (x in 0 until w) {
                val c = gray[off + x]
                val a = Color.alpha(c)
                val g = if (a == 0) 255 else {
                    val r = Color.red(c)
                    val gg = Color.green(c)
                    val b = Color.blue(c)
                    (0.299f * r + 0.587f * gg + 0.114f * b).toInt().coerceIn(0, 255)
                }
                ink[off + x] = g < cfg.inkThresh
            }
        }

        // 連結成分のbbox取得（4近傍）
        val visited = BooleanArray(w * h)
        val boxes = ArrayList<Rect>(64)

        val qx = IntArray(w * h / 8 + 1) // 簡易キュー（足りなければ動的に作り直す）
        val qy = IntArray(qx.size)

        fun enqueueResizeIfNeeded(size: Int): Pair<IntArray, IntArray> {
            if (size <= qx.size) return qx to qy
            // まれに大きいインクで足りない場合だけ増やす
            return IntArray(size) to IntArray(size)
        }

        for (y0 in 0 until h) {
            val off0 = y0 * w
            for (x0 in 0 until w) {
                val idx0 = off0 + x0
                if (!ink[idx0] || visited[idx0]) continue

                var minX = x0
                var maxX = x0
                var minY = y0
                var maxY = y0
                var area = 0

                var head = 0
                var tail = 0

                // BFS
                var (qxLocal, qyLocal) = enqueueResizeIfNeeded(1024)
                qxLocal[tail] = x0
                qyLocal[tail] = y0
                tail++
                visited[idx0] = true

                while (head < tail) {
                    val x = qxLocal[head]
                    val y = qyLocal[head]
                    head++

                    area++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y

                    // 4-neighborhood
                    fun tryPush(nx: Int, ny: Int) {
                        if (nx < 0 || nx >= w || ny < 0 || ny >= h) return
                        val ni = ny * w + nx
                        if (!ink[ni] || visited[ni]) return
                        visited[ni] = true
                        if (tail >= qxLocal.size) {
                            // expand
                            val newSize = qxLocal.size * 2
                            val newX = IntArray(newSize)
                            val newY = IntArray(newSize)
                            for (i in 0 until tail) {
                                newX[i] = qxLocal[i]
                                newY[i] = qyLocal[i]
                            }
                            qxLocal = newX
                            qyLocal = newY
                        }
                        qxLocal[tail] = nx
                        qyLocal[tail] = ny
                        tail++
                    }

                    tryPush(x - 1, y)
                    tryPush(x + 1, y)
                    tryPush(x, y - 1)
                    tryPush(x, y + 1)
                }

                if (area >= cfg.minArea) {
                    boxes.add(Rect(minX, minY, maxX + 1, maxY + 1)) // Rectは right/bottom 排他的
                }
            }
        }

        if (boxes.isEmpty()) return emptyList()

        // 近接クラスタリング（拡張bboxが交差するものは結合）
        val merged = mergeByExpandedIntersection(boxes, cfg.clusterPadPx)

        // 広すぎるbboxは縦投影で分割
        val split = ArrayList<Rect>(merged.size)
        for (r in merged) {
            val bw = r.width()
            val bh = r.height().coerceAtLeast(1)
            val ratio = bw.toFloat() / bh.toFloat()
            if (ratio >= cfg.wideBoxSplitRatio) {
                split.addAll(splitByVerticalProjection(src, ink, w, h, r, cfg))
            } else {
                split.add(r)
            }
        }

        // 整理（小さいもの除去・上限・ソート）
        val cleaned = split
            .filter { it.width() > 2 && it.height() > 2 }
            .sortedBy { it.left }
            .take(cfg.maxChars)

        return cleaned
    }

    private fun mergeByExpandedIntersection(boxes: List<Rect>, pad: Int): List<Rect> {
        if (boxes.isEmpty()) return emptyList()

        val n = boxes.size
        val parent = IntArray(n) { it }

        fun find(x: Int): Int {
            var v = x
            while (parent[v] != v) {
                parent[v] = parent[parent[v]]
                v = parent[v]
            }
            return v
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }

        fun expanded(r: Rect): Rect {
            return Rect(
                r.left - pad,
                r.top - pad,
                r.right + pad,
                r.bottom + pad
            )
        }

        val exp = boxes.map { expanded(it) }

        // O(n^2) だが成分数は多くても数十〜100程度なので現実的
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (Rect.intersects(exp[i], exp[j])) {
                    union(i, j)
                }
            }
        }

        // ルートごとにbboxを合成
        val agg = HashMap<Int, Rect>()
        for (i in 0 until n) {
            val r = boxes[i]
            val root = find(i)
            val cur = agg[root]
            if (cur == null) {
                agg[root] = Rect(r)
            } else {
                cur.left = min(cur.left, r.left)
                cur.top = min(cur.top, r.top)
                cur.right = max(cur.right, r.right)
                cur.bottom = max(cur.bottom, r.bottom)
            }
        }

        return agg.values.toList()
    }

    private fun splitByVerticalProjection(
        src: Bitmap,
        ink: BooleanArray,
        w: Int,
        h: Int,
        box: Rect,
        cfg: SegConfig
    ): List<Rect> {
        val left = box.left.coerceIn(0, w - 1)
        val right = box.right.coerceIn(left + 1, w)
        val top = box.top.coerceIn(0, h - 1)
        val bottom = box.bottom.coerceIn(top + 1, h)

        val bw = right - left
        val bh = bottom - top
        if (bw <= 8 || bh <= 8) return listOf(Rect(left, top, right, bottom))

        // 各列のインク量（縦投影）
        val proj = IntArray(bw)
        for (x in 0 until bw) {
            var sum = 0
            val ax = left + x
            for (y in top until bottom) {
                if (ink[y * w + ax]) sum++
            }
            proj[x] = sum
        }

        // 平滑化（移動平均）
        val sm = IntArray(bw)
        val win = cfg.projectionWindowPx.coerceAtLeast(1)
        for (x in 0 until bw) {
            var s = 0
            var c = 0
            for (k in -win..win) {
                val xx = x + k
                if (xx in 0 until bw) {
                    s += proj[xx]
                    c++
                }
            }
            sm[x] = s / max(1, c)
        }

        // 谷（小さい列）を探す
        val maxV = sm.maxOrNull() ?: 0
        val threshold = (maxV * 0.12f).toInt() // 12%以下を谷候補

        val cutCandidates = ArrayList<Int>()
        var runStart: Int? = null
        for (x in 0 until bw) {
            val isValley = sm[x] <= threshold
            if (isValley) {
                if (runStart == null) runStart = x
            } else {
                if (runStart != null) {
                    val runEnd = x - 1
                    if ((runEnd - runStart + 1) >= cfg.projectionMinGapPx) {
                        // 谷の中央を切断点に
                        cutCandidates.add((runStart + runEnd) / 2)
                    }
                    runStart = null
                }
            }
        }
        if (runStart != null) {
            val runEnd = bw - 1
            if ((runEnd - runStart + 1) >= cfg.projectionMinGapPx) {
                cutCandidates.add((runStart + runEnd) / 2)
            }
        }

        if (cutCandidates.isEmpty()) {
            return listOf(Rect(left, top, right, bottom))
        }

        // 切断点からRect群を作る（最小幅が小さすぎるものは無視）
        val cuts = cutCandidates
            .map { it.coerceIn(2, bw - 3) }
            .distinct()
            .sorted()

        val rects = ArrayList<Rect>()
        var curL = left
        for (c in cuts) {
            val cutX = left + c
            if (cutX - curL >= 6) rects.add(Rect(curL, top, cutX, bottom))
            curL = cutX
        }
        if (right - curL >= 6) rects.add(Rect(curL, top, right, bottom))

        // 各Rectをさらにインクでタイト化（余白削る）
        return rects.mapNotNull { tightByInk(it, ink, w, h) }
    }

    private fun tightByInk(r: Rect, ink: BooleanArray, w: Int, h: Int): Rect? {
        val left = r.left.coerceIn(0, w - 1)
        val right = r.right.coerceIn(left + 1, w)
        val top = r.top.coerceIn(0, h - 1)
        val bottom = r.bottom.coerceIn(top + 1, h)

        var minX = right
        var minY = bottom
        var maxX = -1
        var maxY = -1

        for (y in top until bottom) {
            val off = y * w
            for (x in left until right) {
                if (ink[off + x]) {
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0 || maxY < 0) return null
        return Rect(minX, minY, maxX + 1, maxY + 1)
    }
}

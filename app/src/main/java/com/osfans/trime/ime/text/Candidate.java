/*
 * Copyright (C) 2015-present, osfans
 * waxaca@163.com https://github.com/osfans
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.osfans.trime.ime.text;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.PaintDrawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewParent;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.osfans.trime.core.Rime;
import com.osfans.trime.data.AppPrefs;
import com.osfans.trime.data.Config;
import com.osfans.trime.ime.core.Trime;
import com.osfans.trime.util.GraphicUtils;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

/** 顯示候選字詞 */
public class Candidate extends View {

  /** 處理候選條選字事件 */
  public interface EventListener {
    void onCandidatePressed(int index);

    void onCandidateSymbolPressed(String arrow);

    void onCandidateLongClicked(int index);
  }

  private static final int MAX_CANDIDATE_COUNT = 30;
  public static final String PAGE_UP_BUTTON = "◀";
  public static final String PAGE_DOWN_BUTTON = "▶";
  public static final String PAGE_EX_BUTTON = "▼";
  private int expectWidth = 0;

  private WeakReference<EventListener> listener = new WeakReference<>(null);
  private final GraphicUtils graphicUtils;
  private int highlightIndex = -1;
  private Rime.RimeCandidate[] candidates;
  private final ArrayList<ComputedCandidate> computedCandidates =
      new ArrayList<>(MAX_CANDIDATE_COUNT);
  private int numCandidates;
  private int startNum = 0;
  private long timeDown = 0, timeMove = 0;
  // 點選容錯：按下時的候選＋位置。外層 HorizontalScrollView 只要手指橫移超過 touchSlop
  // 就會攔截觸控（送 CANCEL），快速打字時點候選手指稍微滑一下就整個不選字；
  // 改成小幅滑動不放行，橫移超過 swipeSlop 才交給外層做翻頁／捲動
  private int downIndex = -1;
  private float downX;
  private final int swipeSlop;

  private PaintDrawable candidateHighlight;
  private final Paint separatorPaint;
  private final Paint candidatePaint;
  private final Paint symbolPaint;
  private final Paint commentPaint;
  private Typeface candidateFont, symbolFont, commentFont;
  private int candidateTextColor, hilitedCandidateTextColor;
  private int commentTextColor, hilitedCommentTextColor;
  private int candidateViewHeight, commentHeight, candidateSpacing, candidatePadding;
  private boolean shouldShowComment = true, isCommentOnTop, candidateUseCursor;

  public void reset(Context context) {
    Config config = Config.get(context);
    candidateHighlight = new PaintDrawable(config.getColor("hilited_candidate_back_color"));
    candidateHighlight.setCornerRadius(config.getFloat("layout/round_corner"));
    separatorPaint.setColor(config.getColor("candidate_separator_color"));
    candidateSpacing = config.getPixel("candidate_spacing");
    candidatePadding = config.getPixel("candidate_padding");

    candidateTextColor = config.getColor("candidate_text_color");
    commentTextColor = config.getColor("comment_text_color");
    hilitedCandidateTextColor = config.getColor("hilited_candidate_text_color");
    hilitedCommentTextColor = config.getColor("hilited_comment_text_color");

    int candidate_text_size = config.getPixel("candidate_text_size");
    int comment_text_size = config.getPixel("comment_text_size");
    candidateViewHeight = config.getPixel("candidate_view_height");
    commentHeight = config.getPixel("comment_height");

    candidateFont = config.getFont("candidate_font");
    commentFont = config.getFont("comment_font");
    symbolFont = config.getFont("symbol_font");

    candidatePaint.setTextSize(candidate_text_size);
    candidatePaint.setTypeface(candidateFont);
    symbolPaint.setTextSize(candidate_text_size);
    symbolPaint.setTypeface(symbolFont);
    commentPaint.setTextSize(comment_text_size);
    commentPaint.setTypeface(commentFont);

    isCommentOnTop = config.getBoolean("comment_on_top");
    candidateUseCursor = config.getBoolean("candidate_use_cursor");
    invalidate();
  }

  public void setShowComment(boolean value) {
    shouldShowComment = value;
  }

  public Candidate(Context context, AttributeSet attrs) {
    super(context, attrs);
    swipeSlop = ViewConfiguration.get(context).getScaledTouchSlop() * 3;
    candidatePaint = new Paint();
    candidatePaint.setAntiAlias(true);
    candidatePaint.setStrokeWidth(0);
    symbolPaint = new Paint();
    symbolPaint.setAntiAlias(true);
    symbolPaint.setStrokeWidth(0);
    commentPaint = new Paint();
    commentPaint.setAntiAlias(true);
    commentPaint.setStrokeWidth(0);

    separatorPaint = new Paint();
    separatorPaint.setColor(Color.BLACK);

    graphicUtils = new GraphicUtils(context);

    // reset(context);

    setWillNotDraw(false);
  }

  public static int getMaxCandidateCount() {
    return MAX_CANDIDATE_COUNT;
  }

  public void setCandidateListener(@Nullable EventListener listener) {
    this.listener = new WeakReference<>(listener);
  }

  /**
   * 刷新候選列表
   *
   * @param start 候選的起始編號
   */
  public void setText(int start) {
    startNum = start;
    removeHighlight();
    updateCandidateWidth();
    if (updateCandidates() > 0) {
      invalidate();
    }
  }

  public void setExpectWidth(int expectWidth) {
    this.expectWidth = expectWidth;
  }

  /**
   * 選取候選項
   *
   * @param index 候選項序號（從0開始），{@code -1}表示選擇當前高亮候選項
   * @return 是否成功選字
   */
  private void onCandidateClick(int index, boolean isLongClick) {
    ComputedCandidate candidate = null;
    if (index >= 0 && index < computedCandidates.size()) {
      candidate = computedCandidates.get(index);
      if (candidate != null) {
        if (candidate instanceof ComputedCandidate.Word) {
          if (listener.get() != null) {
            if (isLongClick && Config.getShouldLongClickDeleteCandidate()) {
              listener.get().onCandidateLongClicked(index + startNum);
            } else {
              listener.get().onCandidatePressed(index + startNum);
            }
          }
        }
        if (candidate instanceof ComputedCandidate.Symbol) {
          String arrow = ((ComputedCandidate.Symbol) candidate).getArrow();
          if (listener.get() != null) {
            listener.get().onCandidateSymbolPressed(arrow);
          }
        }
      }
    }
  }

  private void removeHighlight() {
    highlightIndex = -1;
    invalidate();
    requestLayout();
  }

  private boolean isHighlighted(int i) {
    return candidateUseCursor && i == highlightIndex;
  }

  public int getHighlightLeft() {
    if (highlightIndex < computedCandidates.size() && highlightIndex >= 0)
      return computedCandidates.get(highlightIndex).getGeometry().left;
    return 0;
  }

  public int getHighlightRight() {
    if (highlightIndex < computedCandidates.size() && highlightIndex >= 0)
      return computedCandidates.get(highlightIndex).getGeometry().right;
    return 0;
  }

  @Override
  protected void onDraw(Canvas canvas) {
    if (canvas == null) return;
    if (candidates == null) return;
    super.onDraw(canvas);

    for (ComputedCandidate computedCandidate : computedCandidates) {
      int i = computedCandidates.indexOf(computedCandidate);
      // Draw highlight
      if (candidateUseCursor && i == highlightIndex) {
        candidateHighlight.setBounds(computedCandidates.get(i).getGeometry());
        candidateHighlight.draw(canvas);
      }
      // Draw candidates
      if (computedCandidate instanceof ComputedCandidate.Word) {
        float wordX = computedCandidate.getGeometry().centerX();
        float wordY =
            computedCandidates.get(0).getGeometry().centerY()
                - (candidatePaint.ascent() + candidatePaint.descent()) / 2;
        if (shouldShowComment) {
          String comment = ((ComputedCandidate.Word) computedCandidate).getComment();
          if (comment != null && !comment.isEmpty()) {
            float commentX = computedCandidate.getGeometry().centerX();
            float commentY =
                commentHeight / 2.0f - (commentPaint.ascent() + commentPaint.descent()) / 2;
            wordY += commentHeight / 2.0f;
            if (!isCommentOnTop) {
              float commentWidth = graphicUtils.measureText(commentPaint, comment, commentFont);
              commentX = computedCandidate.getGeometry().right - commentWidth / 2;
              commentY += computedCandidates.get(0).getGeometry().bottom - commentHeight;
              wordX -= commentWidth / 2.0f;
              wordY -= commentHeight / 2.0f;
            }
            commentPaint.setColor(isHighlighted(i) ? hilitedCommentTextColor : commentTextColor);
            graphicUtils.drawText(canvas, comment, commentX, commentY, commentPaint, commentFont);
          }
        }
        String word = ((ComputedCandidate.Word) computedCandidate).getWord();
        candidatePaint.setColor(isHighlighted(i) ? hilitedCandidateTextColor : candidateTextColor);
        graphicUtils.drawText(canvas, word, wordX, wordY, candidatePaint, candidateFont);
      } else if (computedCandidate instanceof ComputedCandidate.Symbol) {
        // Draw page up / down buttons
        String arrow = ((ComputedCandidate.Symbol) computedCandidate).getArrow();
        float arrowX =
            computedCandidate.getGeometry().centerX()
                - graphicUtils.measureText(symbolPaint, arrow, symbolFont) / 2;
        float arrowY =
            computedCandidates.get(0).getGeometry().centerY()
                - (candidatePaint.ascent() + candidatePaint.descent()) / 2;
        symbolPaint.setColor(isHighlighted(i) ? hilitedCommentTextColor : commentTextColor);
        canvas.drawText(arrow, arrowX, arrowY, symbolPaint);
      }
      // Draw separators
      if (i + 1 < computedCandidates.size()) {
        canvas.drawRect(
            computedCandidate.getGeometry().right - candidateSpacing,
            computedCandidate.getGeometry().height() * 0.2f,
            computedCandidate.getGeometry().right + candidateSpacing,
            computedCandidate.getGeometry().height() * 0.8f,
            separatorPaint);
      }
    }
  }

  private void updateCandidateWidth() {
    boolean hasExButton = false;
    Integer pageEx =
        Integer.parseInt(AppPrefs.defaultInstance().getKeyboard().getCandidatePageSize()) - 10000;
    int pageBottonWidth =
        (int)
            (candidateSpacing
                + graphicUtils.measureText(symbolPaint, PAGE_DOWN_BUTTON, symbolFont)
                + 2 * candidatePadding);
    int minWidth;
    if (pageEx > 2) minWidth = (int) (expectWidth * (pageEx / 10f + 1) - pageBottonWidth);
    else if (pageEx == 2) minWidth = (expectWidth - pageBottonWidth * 2);
    else minWidth = expectWidth - pageBottonWidth;

    computedCandidates.clear();
    updateCandidates();
    int x = (!Rime.hasLeft()) ? 0 : pageBottonWidth;
    for (int i = 0; i < numCandidates; i++) {
      int n = i + startNum;

      if (pageEx >= 0) {
        if (x >= minWidth) {
          computedCandidates.add(
              new ComputedCandidate.Symbol(
                  PAGE_EX_BUTTON,
                  new Rect(x, 0, ((int) x + pageBottonWidth), getMeasuredHeight())));
          x += pageBottonWidth;
          hasExButton = true;
          break;
        }
      }
      String comment = null, text = candidates[n].text;
      float candidateWidth =
          graphicUtils.measureText(candidatePaint, text, candidateFont) + 2 * candidatePadding;

      if (shouldShowComment) {
        comment = candidates[n].comment;
        if (!TextUtils.isEmpty(comment)) {
          float commentWidth = graphicUtils.measureText(commentPaint, comment, commentFont);
          candidateWidth =
              isCommentOnTop
                  ? Math.max(candidateWidth, commentWidth)
                  : candidateWidth + commentWidth;
        }
      }

      // 自动填满候选栏，并保障展开候选按钮显示出来
      if (pageEx == 0 && x + candidateWidth + candidateSpacing > minWidth) {
        computedCandidates.add(
            new ComputedCandidate.Symbol(
                PAGE_EX_BUTTON, new Rect(x, 0, ((int) x + pageBottonWidth), getMeasuredHeight())));
        x += pageBottonWidth;
        hasExButton = true;
        break;
      }

      computedCandidates.add(
          new ComputedCandidate.Word(
              text, comment, new Rect(x, 0, (int) (x + candidateWidth), getMeasuredHeight())));
      x += candidateWidth + candidateSpacing;
    }
    if (Rime.hasLeft()) {
      computedCandidates.add(
          new ComputedCandidate.Symbol(
              PAGE_UP_BUTTON, new Rect(0, 0, pageBottonWidth, getMeasuredHeight())));
    }
    if (Rime.hasRight()) {
      computedCandidates.add(
          new ComputedCandidate.Symbol(
              PAGE_DOWN_BUTTON, new Rect(x, 0, ((int) x + pageBottonWidth), getMeasuredHeight())));
      x += pageBottonWidth;
    }

    LayoutParams params = getLayoutParams();
    params.width = x;
    params.height =
        (shouldShowComment && isCommentOnTop)
            ? candidateViewHeight + commentHeight
            : candidateViewHeight;
    setLayoutParams(params);

    Trime.getService().setCandidateExPage(hasExButton);
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    updateCandidateWidth();
  }

  @SuppressLint("ClickableViewAccessibility")
  @Override
  public boolean onTouchEvent(@NonNull MotionEvent me) {
    int x = (int) me.getX();
    int y = (int) me.getY();

    final ViewParent parent = getParent();
    switch (me.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        timeDown = System.currentTimeMillis();
        downX = me.getX();
        downIndex = getCandidateIndex(x, y);
        highlightIndex = downIndex;
        setPressed(true);
        if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
        invalidate();
        break;
      case MotionEvent.ACTION_MOVE:
        // 明顯橫滑才放行外層攔截（滑動翻頁、展開候選、捲動長候選列）
        if (parent != null && Math.abs(me.getX() - downX) > swipeSlop) {
          parent.requestDisallowInterceptTouchEvent(false);
        }
        final int moveIndex = getCandidateIndex(x, y);
        if (moveIndex >= 0) highlightIndex = moveIndex; // 滑過候選間隙不清掉高亮
        invalidate();
        break;
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        timeMove = System.currentTimeMillis();
        long durationMs = timeMove - timeDown;
        setPressed(false);
        if (me.getActionMasked() == MotionEvent.ACTION_UP) {
          // 手指拖出候選列外放開＝刻意取消，不選；
          // 放開點落在候選間隙（仍在候選列內）：用按下時的候選
          final boolean inside = y >= 0 && y < getHeight();
          int index = inside ? getCandidateIndex(x, y) : -1;
          if (index < 0 && inside) index = downIndex;
          onCandidateClick(index, durationMs >= Config.getDeleteCandidateTimeout());
        }
        downIndex = -1;
        highlightIndex = -1;
        invalidate();
        break;
    }
    return true;
  }

  /**
   * 獲得觸摸處候選項序號
   *
   * @param x 觸摸點橫座標
   * @param y 觸摸點縱座標
   * @return {@code >=0}: 觸摸點 (x, y) 處候選項序號，從0開始編號； {@code -1}: 觸摸點 (x, y) 處無候選項； {@code -4}: 觸摸點
   *     (x, y) 處爲{@code Page_Up}； {@code -5}: 觸摸點 (x, y) 處爲{@code Page_Down}
   */
  private int getCandidateIndex(int x, int y) {
    // Rect r = new Rect();
    int retIndex = -1;
    // 候選列只有一行：只比橫座標。Rect 高度取自排版當下的 getMeasuredHeight()，
    // 高度尚未量好／過期時用 contains(x, y) 會整排點不到；垂直方向的取消判定在 ACTION_UP
    for (int i = 0; i < computedCandidates.size(); i++) {
      final Rect r = computedCandidates.get(i).getGeometry();
      if (x >= r.left && x < r.right) {
        retIndex = i;
        break;
      }
    }
    return retIndex;
  }

  private int updateCandidates() {
    candidates = Rime.getCandidates();
    highlightIndex = Rime.getCandHighlightIndex() - startNum;
    numCandidates = candidates == null ? 0 : candidates.length - startNum;
    return numCandidates;
  }
}

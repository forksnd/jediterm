package com.jediterm.terminal.model.hyperlinks;

import com.jediterm.terminal.HyperlinkStyle;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.model.CharBuffer;
import com.jediterm.terminal.model.LinesStorage;
import com.jediterm.terminal.model.TerminalLine;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.util.CharUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * @author traff
 */
public class TextProcessing {

  private static final Logger LOG = LoggerFactory.getLogger(TextProcessing.class);

  private final List<HyperlinkFilter> myHyperlinkFilter;
  private TextStyle myHyperlinkColor;
  private HyperlinkStyle.HighlightMode myHighlightMode;
  private TerminalTextBuffer myTerminalTextBuffer;

  public TextProcessing(@NotNull TextStyle hyperlinkColor,
                        @NotNull HyperlinkStyle.HighlightMode highlightMode) {
    myHyperlinkColor = hyperlinkColor;
    myHighlightMode = highlightMode;
    myHyperlinkFilter = new ArrayList<>();
  }

  public void setTerminalTextBuffer(@NotNull TerminalTextBuffer terminalTextBuffer) {
    myTerminalTextBuffer = terminalTextBuffer;
  }

  public void processHyperlinks(@NotNull LinesStorage linesStorage, @NotNull TerminalLine updatedLine) {
    if (myHyperlinkFilter.isEmpty()) return;
    doProcessHyperlinks(linesStorage, updatedLine);
  }

  private void doProcessHyperlinks(@NotNull LinesStorage linesStorage, @NotNull TerminalLine updatedLine) {
    myTerminalTextBuffer.lock();
    try {
      int updatedLineInd = findLineInd(linesStorage, updatedLine);
      if (updatedLineInd == -1) {
        // When lines arrive fast enough, the line might be pushed to the history buffer already.
        updatedLineInd = findHistoryLineInd(myTerminalTextBuffer.getHistoryLinesStorage(), updatedLine);
        if (updatedLineInd == -1) {
          LOG.debug("Cannot find line for links processing");
          return;
        }
        linesStorage = myTerminalTextBuffer.getHistoryLinesStorage();
      }
      int startLineInd = updatedLineInd;
      while (startLineInd > 0 && linesStorage.get(startLineInd - 1).isWrapped()) {
        startLineInd--;
      }
      String lineStr = joinLines(linesStorage, startLineInd, updatedLineInd);
      for (HyperlinkFilter filter : myHyperlinkFilter) {
        LinkResult result = filter.apply(lineStr);
        if (result != null) {
          for (LinkResultItem item : result.getItems()) {
            TextStyle style = new HyperlinkStyle(myHyperlinkColor.getForeground(), myHyperlinkColor.getBackground(),
              item.getLinkInfo(), myHighlightMode, null);
            if (item.getStartOffset() < 0 || item.getEndOffset() > lineStr.length()) continue;

            int prevLinesLength = 0;
            for (int lineInd = startLineInd; lineInd <= updatedLineInd; lineInd++) {
              int startLineOffset = Math.max(prevLinesLength, item.getStartOffset());
              int endLineOffset = Math.min(prevLinesLength + myTerminalTextBuffer.getWidth(), item.getEndOffset());
              if (startLineOffset < endLineOffset) {
                linesStorage.get(lineInd).writeString(startLineOffset - prevLinesLength, new CharBuffer(lineStr.substring(startLineOffset, endLineOffset)), style);
              }
              prevLinesLength += myTerminalTextBuffer.getWidth();
            }
          }
        }
      }
    }
    finally {
      myTerminalTextBuffer.unlock();
    }
  }

  private int findHistoryLineInd(@NotNull LinesStorage historyLinesStorage, @NotNull TerminalLine line) {
    int lastLineInd = Math.max(0, historyLinesStorage.getSize() - 200); // check only last lines in history buffer
    for (int i = historyLinesStorage.getSize() - 1; i >= lastLineInd; i--) {
      if (historyLinesStorage.get(i) == line) {
        return i;
      }
    }
    return -1;
  }

  private static int findLineInd(@NotNull LinesStorage linesStorage, @NotNull TerminalLine line) {
    for (int i = 0; i < linesStorage.getSize(); i++) {
      TerminalLine l = linesStorage.get(i);
      if (l == line) {
        return i;
      }
    }
    return -1;
  }

  @NotNull
  private String joinLines(@NotNull LinesStorage linesStorage, int startLineInd, int updatedLineInd) {
    StringBuilder result = new StringBuilder();
    for (int i = startLineInd; i <= updatedLineInd; i++) {
      String text = linesStorage.get(i).getText();
      if (i < updatedLineInd && text.length() < myTerminalTextBuffer.getWidth()) {
        text = text + new CharBuffer(CharUtils.NUL_CHAR, myTerminalTextBuffer.getWidth() - text.length());
      }
      result.append(text);
    }
    return result.toString();
  }

  public void addHyperlinkFilter(@NotNull HyperlinkFilter filter) {
    myHyperlinkFilter.add(filter);
  }

  public @NotNull List<LinkResultItem> applyFilter(@NotNull String line) {
    List<LinkResultItem> links = new ArrayList<>();
    for (HyperlinkFilter filter : myHyperlinkFilter) {
      LinkResult result = filter.apply(line);
      if (result != null) {
        links.addAll(result.getItems());
      }
    }
    return links;
  }
}

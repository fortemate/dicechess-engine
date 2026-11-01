// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.docs

import java.io.File
import java.nio.file.Files

/** Generates crisp, self-contained SVG chessboards from FEN positions.
  *
  * Eliminates runtime dependencies on external third-party image services (such as Lichess export APIs), preventing
  * HTTP 429 rate-limiting failures on documentation pages containing dozens of board diagrams.
  *
  * Uses the standard vector piece graphics set designed by Colin M.L. Burnett.
  */
object BoardSvgRenderer:

  private val SvgDefs: String =
    """  <defs>
    |    <g id="wP"><path d="M22.5 9c-2.21 0-4 1.79-4 4 0 .89.29 1.71.78 2.38C17.33 16.5 16 18.59 16 21c0 2.03.94 3.84 2.41 5.03-3 1.06-7.41 5.55-7.41 13.47h23c0-7.92-4.41-12.41-7.41-13.47 1.47-1.19 2.41-3 2.41-5.03 0-2.41-1.33-4.5-3.28-5.62.49-.67.78-1.49.78-2.38 0-2.21-1.79-4-4-4z" fill="#fff" stroke="#000" stroke-width="1.5" stroke-linecap="round"/></g>
    |    <g id="wN" fill="none" fill-rule="evenodd" stroke="#000" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M22 10c10.5 1 16.5 8 16 29H15c0-9 10-6.5 8-21" fill="#fff"/><path d="M24 18c.38 2.91-5.55 7.37-8 9-3 2-2.82 4.34-5 4-1.042-.94 1.41-3.04 0-3-1 0 .19 1.23-1 2-1 0-4.003 1-4-4 0-2 6-12 6-12s1.89-1.9 2-3.5c-.73-.994-.5-2-.5-3 1-1 3 2.5 3 2.5h2s.78-1.992 2.5-3c1 0 1 3 1 3" fill="#fff"/><path d="M9.5 25.5a.5.5 0 1 1-1 0 .5.5 0 1 1 1 0zm5.433-9.75a.5 1.5 30 1 1-.866-.5.5 1.5 30 1 1 .866.5z" fill="#000"/></g>
    |    <g id="wB" fill="none" fill-rule="evenodd" stroke="#000" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><g fill="#fff" stroke-linecap="butt"><path d="M9 36c3.39-.97 10.11.43 13.5-2 3.39 2.43 10.11 1.03 13.5 2 0 0 1.65.54 3 2-.68.97-1.65.99-3 .5-3.39-.97-10.11.46-13.5-1-3.39 1.46-10.11.03-13.5 1-1.354.49-2.323.47-3-.5 1.354-1.94 3-2 3-2z"/><path d="M15 32c2.5 2.5 12.5 2.5 15 0 .5-1.5 0-2 0-2 0-2.5-2.5-4-2.5-4 5.5-1.5 6-11.5-5-15.5-11 4-10.5 14-5 15.5 0 0-2.5 1.5-2.5 4 0 0-.5.5 0 2z"/><path d="M25 8a2.5 2.5 0 1 1-5 0 2.5 2.5 0 1 1 5 0z"/></g><path d="M17.5 26h10M15 30h15m-7.5-14.5v5M20 18h5" stroke-linejoin="miter"/></g>
    |    <g id="wR" fill="#fff" fill-rule="evenodd" stroke="#000" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M9 39h27v-3H9v3zm3-3v-4h21v4H12zm-1-22V9h4v2h5V9h5v2h5V9h4v5" stroke-linecap="butt"/><path d="M34 14l-3 3H14l-3-3"/><path d="M31 17v12.5H14V17" stroke-linecap="butt" stroke-linejoin="miter"/><path d="M31 29.5l1.5 2.5h-20l1.5-2.5"/><path d="M11 14h23" fill="none" stroke-linejoin="miter"/></g>
    |    <g id="wQ" fill="#fff" fill-rule="evenodd" stroke="#000" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M8 12a2 2 0 1 1-4 0 2 2 0 1 1 4 0zm16.5-4.5a2 2 0 1 1-4 0 2 2 0 1 1 4 0zM41 12a2 2 0 1 1-4 0 2 2 0 1 1 4 0zM16 8.5a2 2 0 1 1-4 0 2 2 0 1 1 4 0zM33 9a2 2 0 1 1-4 0 2 2 0 1 1 4 0z"/><path d="M9 26c8.5-1.5 21-1.5 27 0l2-12-7 11V11l-5.5 13.5-3-15-3 15-5.5-14V25L7 14l2 12z" stroke-linecap="butt"/><path d="M9 26c0 2 1.5 2 2.5 4 1 1.5 1 1 .5 3.5-1.5 1-1.5 2.5-1.5 2.5-1.5 1.5.5 2.5.5 2.5 6.5 1 16.5 1 23 0 0 0 1.5-1 0-2.5 0 0 .5-1.5-1-2.5-.5-2.5-.5-2 .5-3.5 1-2 2.5-2 2.5-4-8.5-1.5-18.5-1.5-27 0z" stroke-linecap="butt"/><path d="M11.5 30c3.5-1 18.5-1 22 0M12 33.5c6-1 15-1 21 0" fill="none"/></g>
    |    <g id="wK" fill="none" fill-rule="evenodd" stroke="#000" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M22.5 11.63V6M20 8h5" stroke-linejoin="miter"/><path d="M22.5 25s4.5-7.5 3-10.5c0 0-1-2.5-3-2.5s-3 2.5-3 2.5c-1.5 3 3 10.5 3 10.5" fill="#fff" stroke-linecap="butt" stroke-linejoin="miter"/><path d="M11.5 37c5.5 3.5 15.5 3.5 21 0v-7s9-4.5 6-10.5c-4-6.5-13.5-3.5-16 4V27v-3.5c-3.5-7.5-13-10.5-16-4-3 6 5 10 5 10V37z" fill="#fff"/><path d="M11.5 30c5.5-3 15.5-3 21 0m-21 3.5c5.5-3 15.5-3 21 0m-21 3.5c5.5-3 15.5-3 21 0"/></g>
    |    <g id="bP"><path d="M22.5 9c-2.21 0-4 1.79-4 4 0 .89.29 1.71.78 2.38C17.33 16.5 16 18.59 16 21c0 2.03.94 3.84 2.41 5.03-3 1.06-7.41 5.55-7.41 13.47h23c0-7.92-4.41-12.41-7.41-13.47 1.47-1.19 2.41-3 2.41-5.03 0-2.41-1.33-4.5-3.28-5.62.49-.67.78-1.49.78-2.38 0-2.21-1.79-4-4-4z" fill="#000" stroke="#000" stroke-width="1.5" stroke-linecap="round"/></g>
    |    <g id="bN" fill="none" fill-rule="evenodd" stroke="#000" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M22 10c10.5 1 16.5 8 16 29H15c0-9 10-6.5 8-21" fill="#000"/><path d="M24 18c.38 2.91-5.55 7.37-8 9-3 2-2.82 4.34-5 4-1.042-.94 1.41-3.04 0-3-1 0 .19 1.23-1 2-1 0-4.003 1-4-4 0-2 6-12 6-12s1.89-1.9 2-3.5c-.73-.994-.5-2-.5-3 1-1 3 2.5 3 2.5h2s.78-1.992 2.5-3c1 0 1 3 1 3" fill="#000"/><path d="M9.5 25.5a.5.5 0 1 1-1 0 .5.5 0 1 1 1 0zm5.433-9.75a.5 1.5 30 1 1-.866-.5.5 1.5 30 1 1 .866.5z" fill="#ececec" stroke="#ececec"/><path d="M24.55 10.4l-.45 1.45.5.15c3.15 1 5.65 2.49 7.9 6.75S35.75 29.06 35.25 39l-.05.5h2.25l.05-.5c.5-10.06-.88-16.85-3.25-21.34-2.37-4.49-5.79-6.64-9.19-7.16l-.51-.1z" fill="#ececec" stroke="none"/></g>
    |    <g id="bB" fill="none" fill-rule="evenodd" stroke="#000" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><g fill="#000" stroke-linecap="butt"><path d="M9 36c3.39-.97 10.11.43 13.5-2 3.39 2.43 10.11 1.03 13.5 2 0 0 1.65.54 3 2-.68.97-1.65.99-3 .5-3.39-.97-10.11.46-13.5-1-3.39 1.46-10.11.03-13.5 1-1.354.49-2.323.47-3-.5 1.354-1.94 3-2 3-2z"/><path d="M15 32c2.5 2.5 12.5 2.5 15 0 .5-1.5 0-2 0-2 0-2.5-2.5-4-2.5-4 5.5-1.5 6-11.5-5-15.5-11 4-10.5 14-5 15.5 0 0-2.5 1.5-2.5 4 0 0-.5.5 0 2z"/><path d="M25 8a2.5 2.5 0 1 1-5 0 2.5 2.5 0 1 1 5 0z"/></g><path d="M17.5 26h10M15 30h15m-7.5-14.5v5M20 18h5" stroke="#ececec" stroke-linejoin="miter"/></g>
    |    <g id="bR" fill-rule="evenodd" stroke="#000" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M9 39h27v-3H9v3zm3.5-7l1.5-2.5h17l1.5 2.5h-20zm-.5 4v-4h21v4H12z" stroke-linecap="butt" fill="#000"/><path d="M14 29.5v-13h17v13H14z" stroke-linecap="butt" stroke-linejoin="miter" fill="#000"/><path d="M14 16.5L11 14h23l-3 2.5H14zM11 14V9h4v2h5V9h5v2h5V9h4v5H11z" stroke-linecap="butt" fill="#000"/><path d="M12 35.5h21m-20-4h19m-18-2h17m-17-13h17M11 14h23" fill="none" stroke="#ececec" stroke-width="1" stroke-linejoin="miter"/></g>
    |    <g id="bQ" fill-rule="evenodd" stroke="#000" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><g stroke="none" fill="#000"><circle cx="6" cy="12" r="2.75"/><circle cx="14" cy="9" r="2.75"/><circle cx="22.5" cy="8" r="2.75"/><circle cx="31" cy="9" r="2.75"/><circle cx="39" cy="12" r="2.75"/></g><path d="M9 26c8.5-1.5 21-1.5 27 0l2.5-12.5L31 25l-.3-14.1-5.2 13.6-3-14.5-3 14.5-5.2-13.6L14 25 6.5 13.5 9 26z" stroke-linecap="butt" fill="#000"/><path d="M9 26c0 2 1.5 2 2.5 4 1 1.5 1 1 .5 3.5-1.5 1-1.5 2.5-1.5 2.5-1.5 1.5.5 2.5.5 2.5 6.5 1 16.5 1 23 0 0 0 1.5-1 0-2.5 0 0 .5-1.5-1-2.5-.5-2.5-.5-2 .5-3.5 1-2 2.5-2 2.5-4-8.5-1.5-18.5-1.5-27 0z" stroke-linecap="butt" fill="#000"/><path d="M11 38.5a35 35 1 0 0 23 0" fill="none" stroke-linecap="butt"/><path d="M11 29a35 35 1 0 1 23 0m-21.5 2.5h20m-21 3a35 35 1 0 0 22 0m-23 3a35 35 1 0 0 24 0" fill="none" stroke="#ececec"/></g>
    |    <g id="bK" fill="none" fill-rule="evenodd" stroke="#000" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M22.5 11.63V6" stroke-linejoin="miter"/><path d="M22.5 25s4.5-7.5 3-10.5c0 0-1-2.5-3-2.5s-3 2.5-3 2.5c-1.5 3 3 10.5 3 10.5" fill="#000" stroke-linecap="butt" stroke-linejoin="miter"/><path d="M11.5 37c5.5 3.5 15.5 3.5 21 0v-7s9-4.5 6-10.5c-4-6.5-13.5-3.5-16 4V27v-3.5c-3.5-7.5-13-10.5-16-4-3 6 5 10 5 10V37z" fill="#000"/><path d="M20 8h5" stroke-linejoin="miter"/><path d="M32 29.5s8.5-4 6.03-9.65C34.15 14 25 18 22.5 24.5l.01 2.1-.01-2.1C20 18 9.906 14 6.997 19.85c-2.497 5.65 4.853 9 4.853 9" stroke="#ececec"/><path d="M11.5 30c5.5-3 15.5-3 21 0m-21 3.5c5.5-3 15.5-3 21 0m-21 3.5c5.5-3 15.5-3 21 0" stroke="#ececec"/></g>
    |  </defs>""".stripMargin

  private def renderDarkSquares: String =
    val squares = for
      row <- 0 until 8
      col <- 0 until 8
      if (col + row) % 2 == 1
    yield
      val x = col * 45
      val y = row * 45
      s"""    <rect x="$x" y="$y" width="45" height="45"/>"""
    squares.mkString("  <g fill=\"#b58863\">\n", "\n", "\n  </g>")

  private def renderFileLabels(flipped: Boolean): Seq[String] =
    for col <- 0 until 8 yield
      val fileChar = if flipped then ('h' - col).toChar else ('a' + col).toChar
      val fill     = if (col + 7) % 2 == 1 then "#f0d9b5" else "#b58863"
      val x        = col * 45 + 3
      val y        = 7 * 45 + 42
      s"""    <text x="$x" y="$y" fill="$fill">$fileChar</text>"""

  private def renderRankLabels(flipped: Boolean): Seq[String] =
    for row <- 0 until 8 yield
      val rankNum = if flipped then row + 1 else 8 - row
      val fill    = if (7 + row) % 2 == 1 then "#f0d9b5" else "#b58863"
      val x       = 7 * 45 + 37
      val y       = row * 45 + 11
      s"""    <text x="$x" y="$y" fill="$fill">$rankNum</text>"""

  private def renderCoordinates(flipped: Boolean): String =
    val labels = renderFileLabels(flipped) ++ renderRankLabels(flipped)
    labels.mkString(
      "  <g font-family=\"-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif\" font-size=\"10\" font-weight=\"bold\">\n",
      "\n",
      "\n  </g>"
    )

  private def charToPieceId(c: Char): Option[String] = c match
    case 'P' => Some("wP")
    case 'N' => Some("wN")
    case 'B' => Some("wB")
    case 'R' => Some("wR")
    case 'Q' => Some("wQ")
    case 'K' => Some("wK")
    case 'p' => Some("bP")
    case 'n' => Some("bN")
    case 'b' => Some("bB")
    case 'r' => Some("bR")
    case 'q' => Some("bQ")
    case 'k' => Some("bK")
    case _   => None

  private def boardCoord(idx: Int, flipped: Boolean): Int =
    if flipped then 7 - idx else idx

  private def renderPieceUse(pieceId: String, col: Int, rankIndex: Int, flipped: Boolean): Option[String] =
    if col < 0 || col >= 8 then None
    else
      val x = boardCoord(col, flipped) * 45
      val y = boardCoord(rankIndex, flipped) * 45
      Some(s"""    <use href="#$pieceId" x="$x" y="$y"/>""")

  private def renderRankPieces(rankStr: String, rankIndex: Int, flipped: Boolean): Seq[String] =
    val result = scala.collection.mutable.ArrayBuffer.empty[String]
    var col    = 0
    for c <- rankStr do
      if c.isDigit then col += c - '0'
      else
        for
          pieceId <- charToPieceId(c)
          useTag  <- renderPieceUse(pieceId, col, rankIndex, flipped)
        do result += useTag
        col += 1
    result.toSeq

  private def renderPieces(fen: String, flipped: Boolean): String =
    val placement = fen.trim.split(" ").headOption.getOrElse("")
    val ranks     = placement.split("/").take(8)
    val uses      = for
      (rankStr, rankIndex) <- ranks.zipWithIndex
      useTag               <- renderRankPieces(rankStr, rankIndex, flipped)
    yield useTag
    uses.mkString("  <g>\n", "\n", "\n  </g>")

  /** Renders an SVG chessboard from the given FEN string.
    *
    * @param fen
    *   the FEN or DFEN string (only the piece placement field is used to place pieces)
    * @param flipped
    *   if true, renders from Black's perspective (h1 at top-left, a8 at bottom-right)
    * @return
    *   the full SVG document content
    */
  def render(fen: String, flipped: Boolean = false): String =
    val sb = new StringBuilder()
    sb.append(
      """<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 360 360" width="100%" height="100%">""" + "\n"
    )
    sb.append(SvgDefs).append("\n")
    sb.append("""  <rect width="360" height="360" fill="#f0d9b5"/>""" + "\n")
    sb.append(renderDarkSquares).append("\n")
    sb.append(renderCoordinates(flipped)).append("\n")
    sb.append(renderPieces(fen, flipped)).append("\n")
    sb.append("</svg>\n")
    sb.toString()

  /** Renders the board SVG and saves it to the specified file. */
  def saveToFile(file: File, fen: String, flipped: Boolean = false): Unit =
    file.getParentFile.mkdirs()
    Files.writeString(file.toPath, render(fen, flipped))

#!/usr/bin/env python3
"""Generate figures for the NIER paper.

Outputs:
  fig_cross_model.pdf  -- Sonnet 4 vs DeepSeek per-category distribution
  fig_crud_bridge.pdf  -- 62 CRUD failures -> 45 below L6 vs 17 mapped to taxonomy

Requires only matplotlib; no seaborn.
"""
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
import numpy as np
from pathlib import Path

OUT = Path(__file__).parent

CATEGORIES = ["A.1", "A.2", "B", "C.1", "C.2"]
SONNET = [32.5, 8.75, 16.25, 21.25, 21.25]      # paper (N=80)
DEEPSEEK = [35.9, 9.0, 16.7, 15.4, 23.1]        # run #5 (N=78)


def fig_cross_model():
    x = np.arange(len(CATEGORIES))
    w = 0.38
    fig, ax = plt.subplots(figsize=(5.5, 2.6))
    b1 = ax.bar(x - w/2, SONNET, w, label="Claude Sonnet 4 (N=80)",
                edgecolor="black", linewidth=0.5, color="#5B8DEF")
    b2 = ax.bar(x + w/2, DEEPSEEK, w, label="DeepSeek-Chat (N=78)",
                edgecolor="black", linewidth=0.5, color="#F4A261")
    ax.set_xticks(x)
    ax.set_xticklabels(CATEGORIES)
    ax.set_ylabel("% of failing cases")
    ax.set_ylim(0, 45)
    ax.legend(frameon=False, fontsize=9, loc="upper right")
    ax.spines["top"].set_visible(False)
    ax.spines["right"].set_visible(False)
    for bars in (b1, b2):
        for rect in bars:
            h = rect.get_height()
            ax.annotate(f"{h:.1f}", xy=(rect.get_x() + rect.get_width()/2, h),
                        xytext=(0, 2), textcoords="offset points",
                        ha="center", va="bottom", fontsize=7)
    plt.tight_layout()
    plt.savefig(OUT / "fig_cross_model.pdf")
    plt.savefig(OUT / "fig_cross_model.png", dpi=150)
    plt.close()
    print("wrote fig_cross_model.{pdf,png}")


def fig_crud_bridge():
    """100-case CRUD-Level-6 corpus -> 71 failures -> 5 leaves (Run #6 data)."""
    fig, ax = plt.subplots(figsize=(6.2, 3.4))
    ax.axis("off")

    # title (kept inside the figure box, top centred)
    ax.text(0.50, 0.97, "Cross-domain mapping (CRUD-Level-6 corpus)",
            ha="center", va="center", fontsize=11, fontweight="bold")

    # left: 100 CRUD cases
    ax.add_patch(plt.Rectangle((0.02, 0.40), 0.18, 0.26,
                               facecolor="#E8E8E8", edgecolor="black"))
    ax.text(0.11, 0.53, "100 CRUD\ncases", ha="center", va="center",
            fontsize=10, fontweight="bold")

    # arrows to two outcomes
    ax.annotate("", xy=(0.36, 0.78), xytext=(0.20, 0.60),
                arrowprops=dict(arrowstyle="->", lw=1.2, color="#888"))
    ax.annotate("", xy=(0.36, 0.24), xytext=(0.20, 0.46),
                arrowprops=dict(arrowstyle="->", lw=1.5, color="#444"))

    # passes box (greyed)
    ax.add_patch(plt.Rectangle((0.36, 0.66), 0.28, 0.22,
                               facecolor="#F5F5F5", edgecolor="grey", linestyle="--"))
    ax.text(0.50, 0.82, "29 passing", ha="center", va="center",
            fontsize=10, color="grey", fontweight="bold")
    ax.text(0.50, 0.72, "(LLM resolved\nambiguity correctly)",
            ha="center", va="center", fontsize=8, color="grey", style="italic")

    # Failures box (highlighted)
    ax.add_patch(plt.Rectangle((0.36, 0.08), 0.28, 0.32,
                               facecolor="#FFE8B8", edgecolor="#B07000", linewidth=1.2))
    ax.text(0.50, 0.34, "71 failures", ha="center", va="center",
            fontsize=11, fontweight="bold")
    ax.text(0.50, 0.23, "64 Level 6 (90.1%)\n+ 7 compilation",
            ha="center", va="center", fontsize=8, style="italic")
    ax.text(0.50, 0.12, "(EXEC_ASSERTION_MISMATCH)",
            ha="center", va="center", fontsize=7, color="#553000")

    # right: 5 leaf categories - pulled inward, narrower, evenly spaced
    leaves = [("A.1", 29, 0.84), ("A.2", 6, 0.69), ("B", 10, 0.54),
              ("C.1", 11, 0.39), ("C.2", 15, 0.24)]
    for cat, cnt, y in leaves:
        ax.add_patch(plt.Rectangle((0.74, y - 0.055), 0.13, 0.11,
                                   facecolor="#D4EDDA", edgecolor="#155724"))
        ax.text(0.805, y, f"{cat}: {cnt}", ha="center", va="center",
                fontsize=9, fontweight="bold")
        ax.annotate("", xy=(0.74, y), xytext=(0.64, 0.24),
                    arrowprops=dict(arrowstyle="->", lw=0.7, color="#155724"))

    # caption under the leaf stack, kept well inside x=1.0
    ax.text(0.805, 0.10, "5 taxonomy leaves",
            ha="center", va="center", fontsize=8, style="italic", color="#155724")

    ax.set_xlim(0, 1)
    ax.set_ylim(0, 1)
    plt.tight_layout()
    plt.savefig(OUT / "fig_crud_bridge.pdf")
    plt.savefig(OUT / "fig_crud_bridge.png", dpi=150)
    plt.close()
    print("wrote fig_crud_bridge.{pdf,png}")


def fig_mitigation():
    """Pre/post mitigation pass rates from Run #5 (pre) vs Run #7 (post)."""
    cats = ["A.1", "A.2", "B", "C.1", "C.2"]
    # 12 scenarios per category (3 cases x 4 scenarios)
    pre_count  = [1,  6, 3, 4,  2]   # passes / 12
    post_count = [9,  6, 6, 9, 10]   # passes / 12
    pre  = [100 * c / 12 for c in pre_count]
    post = [100 * c / 12 for c in post_count]

    x = np.arange(len(cats))
    w = 0.38
    fig, ax = plt.subplots(figsize=(5.5, 2.7))
    b1 = ax.bar(x - w/2, pre,  w, label="Pre-mitigation",
                edgecolor="black", linewidth=0.5, color="#E76F51")
    b2 = ax.bar(x + w/2, post, w, label="Post-mitigation",
                edgecolor="black", linewidth=0.5, color="#2A9D8F")
    ax.set_xticks(x); ax.set_xticklabels(cats)
    ax.set_ylabel("Pass rate (%)")
    ax.set_ylim(0, 105)
    ax.legend(frameon=False, fontsize=9, loc="upper left")
    ax.spines["top"].set_visible(False); ax.spines["right"].set_visible(False)
    for bars in (b1, b2):
        for rect in bars:
            h = rect.get_height()
            ax.annotate(f"{h:.0f}", xy=(rect.get_x() + rect.get_width()/2, h),
                        xytext=(0, 2), textcoords="offset points",
                        ha="center", va="bottom", fontsize=8)
    # Total annotation
    ax.text(0.99, 0.96,
            f"Total: 27% → 67% (+40 pp)",
            transform=ax.transAxes, ha="right", va="top",
            fontsize=9, fontweight="bold",
            bbox=dict(boxstyle="round,pad=0.3", facecolor="white", edgecolor="grey"))
    plt.tight_layout()
    plt.savefig(OUT / "fig_mitigation.pdf")
    plt.savefig(OUT / "fig_mitigation.png", dpi=150)
    plt.close()
    print("wrote fig_mitigation.{pdf,png}")


def fig_decision_tree():
    """Decision tree sized to fit a single IEEEtran column at 1:1 (figsize ~ columnwidth)."""
    fig, ax = plt.subplots(figsize=(3.4, 4.4))
    ax.axis("off")
    ax.set_xlim(-0.3, 11.3)
    ax.set_ylim(0, 12)

    YEL = "#FFF6CC"
    GRN = "#D4EDDA"
    EDG = "#155724"
    LIN = "#333333"

    # Node x-positions (logical columns) — leaves are 1.8 wide, gaps >= 0.7
    X_A1, X_A2L, X_B, X_CC, X_A2R = 0.7, 3.2, 5.7, 7.4, 10.0
    X_Q1A = (X_A1 + X_A2L) / 2
    X_Q3  = (X_CC + X_A2R) / 2
    X_Q2  = (X_B + X_Q3) / 2
    X_Q1  = (X_Q1A + X_Q2) / 2

    # Node y-positions (rows) — taller layout for portrait orientation
    Y_ROOT, Y_L2, Y_L3, Y_L4 = 11.0, 8.2, 4.4, 1.4

    def diamond(cx, cy, w, h, text, fc=YEL):
        pts = [(cx, cy + h/2), (cx + w/2, cy), (cx, cy - h/2), (cx - w/2, cy)]
        ax.add_patch(plt.Polygon(pts, closed=True, facecolor=fc,
                                  edgecolor="black", linewidth=1.0, zorder=3))
        ax.text(cx, cy, text, ha="center", va="center",
                fontsize=11, fontweight="bold", zorder=4)

    def leaf(cx, cy, w, h, text, fc=GRN):
        ax.add_patch(mpatches.FancyBboxPatch((cx - w/2, cy - h/2), w, h,
                                              boxstyle="round,pad=0.02",
                                              facecolor=fc, edgecolor=EDG,
                                              linewidth=1.0, zorder=3))
        ax.text(cx, cy, text, ha="center", va="center",
                fontsize=8, fontweight="bold", zorder=4)

    def lshape(x_par, y_par_bottom, x_chi, y_chi_top, label):
        y_mid = (y_par_bottom + y_chi_top) / 2
        ax.plot([x_par, x_par], [y_par_bottom, y_mid], color=LIN, lw=0.9, zorder=2)
        ax.plot([x_par, x_chi], [y_mid, y_mid], color=LIN, lw=0.9, zorder=2)
        ax.annotate("", xy=(x_chi, y_chi_top), xytext=(x_chi, y_mid),
                    arrowprops=dict(arrowstyle="->", lw=0.9, color=LIN), zorder=2)
        lx = (x_par + x_chi) / 2
        ax.text(lx, y_mid + 0.35, label, ha="center", va="center",
                fontsize=8, color="#222",
                bbox=dict(boxstyle="round,pad=0.15", facecolor="white",
                          edgecolor="#bbb", linewidth=0.3, alpha=0.95),
                zorder=5)

    # Diamond/leaf sizes — small text means small shapes
    DW, DH = 1.4, 1.4
    LW, LH = 1.8, 1.4

    # --- nodes (abbreviated; full questions in caption) ---
    diamond(X_Q1,  Y_ROOT, DW, DH, "Q1")
    diamond(X_Q1A, Y_L2,   DW, DH, "Q1a")
    diamond(X_Q2,  Y_L2,   DW, DH, "Q2")
    diamond(X_Q3,  Y_L3,   DW, DH, "Q3")

    leaf(X_A1,  Y_L3, LW, LH, "A.1\nLexical")
    leaf(X_A2L, Y_L3, LW, LH, "A.2\nDef. Gap")
    leaf(X_B,   Y_L3, LW, LH, "B\nPolysem.")
    leaf(X_CC,  Y_L4, LW, LH, "C.1 / C.2\nIncomplete")
    leaf(X_A2R, Y_L4, LW, LH, "A.2\nDef. Gap")

    # --- edges (L-shaped) ---
    lshape(X_Q1,  Y_ROOT - DH/2, X_Q1A, Y_L2  + DH/2, "No")
    lshape(X_Q1,  Y_ROOT - DH/2, X_Q2,  Y_L2  + DH/2, "Yes")
    lshape(X_Q1A, Y_L2  - DH/2, X_A1,  Y_L3  + LH/2, "No")
    lshape(X_Q1A, Y_L2  - DH/2, X_A2L, Y_L3  + LH/2, "Yes")
    lshape(X_Q2,  Y_L2  - DH/2, X_B,   Y_L3  + LH/2, "Yes")
    lshape(X_Q2,  Y_L2  - DH/2, X_Q3,  Y_L3  + DH/2, "No")
    lshape(X_Q3,  Y_L3  - DH/2, X_CC,  Y_L4  + LH/2, "Yes")
    lshape(X_Q3,  Y_L3  - DH/2, X_A2R, Y_L4  + LH/2, "No")

    plt.tight_layout()
    plt.savefig(OUT / "fig_decision_tree.pdf")
    plt.savefig(OUT / "fig_decision_tree.png", dpi=150)
    plt.close()
    print("wrote fig_decision_tree.{pdf,png}")


if __name__ == "__main__":
    fig_decision_tree()
    fig_cross_model()
    fig_crud_bridge()
    fig_mitigation()

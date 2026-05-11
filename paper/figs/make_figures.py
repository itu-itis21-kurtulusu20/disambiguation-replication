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
    fig, ax = plt.subplots(figsize=(5.5, 3.0))
    ax.axis("off")

    # left: 100 CRUD cases
    ax.add_patch(plt.Rectangle((0.02, 0.35), 0.20, 0.30,
                               facecolor="#E8E8E8", edgecolor="black"))
    ax.text(0.12, 0.50, "100 CRUD\ncases", ha="center", va="center",
            fontsize=10, fontweight="bold")

    # arrows to two outcomes
    ax.annotate("", xy=(0.40, 0.78), xytext=(0.22, 0.55),
                arrowprops=dict(arrowstyle="->", lw=1.2, color="#888"))
    ax.annotate("", xy=(0.40, 0.22), xytext=(0.22, 0.45),
                arrowprops=dict(arrowstyle="->", lw=1.5, color="#444"))

    # passes box (greyed)
    ax.add_patch(plt.Rectangle((0.40, 0.66), 0.30, 0.24,
                               facecolor="#F5F5F5", edgecolor="grey", linestyle="--"))
    ax.text(0.55, 0.83, "29 passing", ha="center", va="center",
            fontsize=10, color="grey", fontweight="bold")
    ax.text(0.55, 0.73, "(LLM resolved\nambiguity\ncorrectly)",
            ha="center", va="center", fontsize=8, color="grey", style="italic")

    # Failures box (highlighted)
    ax.add_patch(plt.Rectangle((0.40, 0.06), 0.30, 0.34,
                               facecolor="#FFE8B8", edgecolor="#B07000", linewidth=1.2))
    ax.text(0.55, 0.34, "71 failures", ha="center", va="center",
            fontsize=11, fontweight="bold")
    ax.text(0.55, 0.22, "64 Level 6 (90.1%)\n+ 7 compilation",
            ha="center", va="center", fontsize=8, style="italic")
    ax.text(0.55, 0.10, "(EXEC\\_ASSERTION\\_MISMATCH)".replace("\\_","_"),
            ha="center", va="center", fontsize=7, color="#553000")

    # right: 5 leaf categories with actual counts
    leaves = [("A.1", 29, 0.88), ("A.2", 6, 0.71), ("B", 10, 0.54),
              ("C.1", 11, 0.37), ("C.2", 15, 0.20)]
    for cat, cnt, y in leaves:
        ax.add_patch(plt.Rectangle((0.82, y - 0.06), 0.14, 0.12,
                                   facecolor="#D4EDDA", edgecolor="#155724"))
        ax.text(0.89, y, f"{cat}\n{cnt}", ha="center", va="center",
                fontsize=8, fontweight="bold")
        ax.annotate("", xy=(0.82, y), xytext=(0.70, 0.23),
                    arrowprops=dict(arrowstyle="->", lw=0.7, color="#155724"))

    ax.text(0.55, 0.96, "Cross-domain mapping (CRUD-Level-6 corpus)",
            ha="center", va="center", fontsize=11, fontweight="bold")
    ax.text(0.89, 0.04, "5 taxonomy\nleaves",
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
    """Render the decision tree as a clean matplotlib figure (PDF-embeddable)."""
    fig, ax = plt.subplots(figsize=(5.5, 3.0))
    ax.axis("off")
    ax.set_xlim(0, 1); ax.set_ylim(0, 1)

    def diamond(cx, cy, w, h, text, fc="#FFF6CC"):
        pts = [(cx, cy + h/2), (cx + w/2, cy), (cx, cy - h/2), (cx - w/2, cy)]
        ax.add_patch(plt.Polygon(pts, closed=True, facecolor=fc,
                                  edgecolor="black", linewidth=1.0))
        ax.text(cx, cy, text, ha="center", va="center", fontsize=8)

    def leaf(cx, cy, w, h, text, fc="#D4EDDA"):
        ax.add_patch(plt.Rectangle((cx - w/2, cy - h/2), w, h,
                                    facecolor=fc, edgecolor="#155724",
                                    linewidth=1.0))
        ax.text(cx, cy, text, ha="center", va="center",
                fontsize=8, fontweight="bold")

    def edge(x1, y1, x2, y2, label="", side="left"):
        ax.annotate("", xy=(x2, y2), xytext=(x1, y1),
                    arrowprops=dict(arrowstyle="->", lw=0.8, color="#444"))
        if label:
            mx, my = (x1 + x2) / 2, (y1 + y2) / 2
            dx = -0.025 if side == "left" else 0.025
            ax.text(mx + dx, my, label, ha="center", va="center",
                    fontsize=7, color="#333")

    diamond(0.50, 0.92, 0.30, 0.10, "Q1: Standard\nmath definition?")
    diamond(0.20, 0.65, 0.26, 0.10, "Q1a: Only\nbehavior?")
    diamond(0.72, 0.65, 0.26, 0.10, "Q2: Multiple\ndefinitions?")
    leaf(0.07, 0.30, 0.16, 0.10, "A.1\nLexical Gap")
    leaf(0.27, 0.30, 0.16, 0.10, "A.2\nDef. Gap")
    leaf(0.55, 0.42, 0.14, 0.10, "B\nPolysemous")
    diamond(0.80, 0.30, 0.26, 0.10, "Q3: Wrong\ncomponent?")
    leaf(0.66, 0.05, 0.16, 0.10, "C\nIncomplete")
    leaf(0.92, 0.05, 0.16, 0.10, "A.2\nDef. Gap")

    edge(0.43, 0.89, 0.27, 0.70, "No",  "left")
    edge(0.57, 0.89, 0.65, 0.70, "Yes", "right")
    edge(0.13, 0.60, 0.07, 0.36, "No",  "left")
    edge(0.27, 0.60, 0.27, 0.36, "Yes", "right")
    edge(0.65, 0.60, 0.55, 0.48, "Yes", "left")
    edge(0.78, 0.60, 0.80, 0.36, "No",  "right")
    edge(0.73, 0.25, 0.66, 0.11, "Yes", "left")
    edge(0.87, 0.25, 0.92, 0.11, "No",  "right")

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

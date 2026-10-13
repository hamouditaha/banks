import { Component, computed, input, signal } from '@angular/core';

export interface Bar {
  /** Short axis label, e.g. "lun. 12". */
  label: string;
  value: number;
  /** Extra line shown in the tooltip. */
  detail?: string;
}

/**
 * Single-series column chart (no legend: the card title names the series). Bars grow from a shared
 * baseline; the tallest one is labelled directly and every bar shows its value on hover or focus.
 */
@Component({
  selector: 'app-bar-chart',
  template: `
    <div class="bar-chart" role="img" [attr.aria-label]="summary()">
      <div class="bar-plot" [style.height.px]="height()">
        @for (bar of bars(); track bar.label; let i = $index) {
          <div class="bar-slot" tabindex="0"
               (mouseenter)="active.set(i)" (mouseleave)="active.set(null)"
               (focus)="active.set(i)" (blur)="active.set(null)">
            @if (active() === i) {
              <div class="bar-tooltip" role="tooltip" [style.bottom]="above(bar.value)">
                <strong>{{ bar.value }} {{ unit() }}</strong>
                @if (bar.detail) {
                  <span>{{ bar.detail }}</span>
                }
              </div>
            } @else if (i === peak() && bar.value > 0) {
              <span class="bar-value" [style.bottom]="above(bar.value)">{{ bar.value }}</span>
            }
            <div class="bar" [class.bar-active]="active() === i" [style.height.%]="percent(bar.value)"></div>
          </div>
        }
      </div>
      <div class="bar-axis">
        @for (bar of bars(); track bar.label) {
          <span>{{ bar.label }}</span>
        }
      </div>
    </div>
  `,
})
export class BarChartComponent {
  readonly bars = input.required<Bar[]>();
  /** Plural unit used in the tooltip and the accessible summary, e.g. "virements". */
  readonly unit = input('');
  readonly height = input(160);

  readonly active = signal<number | null>(null);
  readonly max = computed(() => Math.max(0, ...this.bars().map((b) => b.value)));
  readonly peak = computed(() => this.bars().findIndex((b) => b.value === this.max()));
  readonly summary = computed(() => this.bars().map((b) => `${b.label} : ${b.value} ${this.unit()}`).join(', '));

  /** Bar height as a share of the plot; empty bars keep a CSS min-height stub on the baseline. */
  percent(value: number): number {
    const max = this.max();
    return max === 0 ? 0 : (value / max) * 100;
  }

  /** Places a label just above the top of the bar. */
  above(value: number): string {
    return `calc(${this.percent(value)}% + 6px)`;
  }
}

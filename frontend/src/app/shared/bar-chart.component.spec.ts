import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Bar, BarChartComponent } from './bar-chart.component';

describe('BarChartComponent', () => {
  let fixture: ComponentFixture<BarChartComponent>;
  const el = () => fixture.nativeElement as HTMLElement;

  function render(bars: Bar[]): void {
    fixture = TestBed.createComponent(BarChartComponent);
    fixture.componentRef.setInput('bars', bars);
    fixture.componentRef.setInput('unit', 'virements');
    fixture.detectChanges();
  }

  it('scales bars against the tallest one and labels only the peak', () => {
    render([
      { label: 'lun.', value: 2 },
      { label: 'mar.', value: 4 },
      { label: 'mer.', value: 0 },
    ]);

    const heights = [...el().querySelectorAll<HTMLElement>('.bar')].map((b) => b.style.height);
    expect(heights).toEqual(['50%', '100%', '0%']);
    const labels = el().querySelectorAll('.bar-value');
    expect(labels.length).toBe(1);
    expect(labels[0].textContent?.trim()).toBe('4');
    expect(el().querySelector('.bar-chart')?.getAttribute('aria-label')).toContain('mar. : 4 virements');
  });

  it('shows a tooltip on hover', () => {
    render([{ label: 'lun.', value: 3, detail: '300 au total' }]);

    el().querySelector('.bar-slot')!.dispatchEvent(new Event('mouseenter'));
    fixture.detectChanges();

    const tooltip = el().querySelector('.bar-tooltip');
    expect(tooltip?.textContent).toContain('3 virements');
    expect(tooltip?.textContent).toContain('300 au total');
  });

  it('draws flat bars when every value is zero', () => {
    render([{ label: 'lun.', value: 0 }]);

    expect(el().querySelector<HTMLElement>('.bar')!.style.height).toBe('0%');
    expect(el().querySelector('.bar-value')).toBeNull();
  });
});

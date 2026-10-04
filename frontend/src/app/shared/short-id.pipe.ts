import { Pipe, PipeTransform } from '@angular/core';

/** Shortens a UUID to its first block, e.g. "3f2a9c1e". */
@Pipe({ name: 'shortId' })
export class ShortIdPipe implements PipeTransform {
  transform(value: string | null | undefined): string {
    return value ? value.split('-')[0] : '';
  }
}

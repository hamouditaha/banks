import { toCsv } from './csv';

describe('toCsv', () => {
  it('joins cells with semicolons and rows with CRLF', () => {
    expect(toCsv([['a', 'b'], [1, 2]])).toBe('a;b\r\n1;2');
  });

  it('quotes cells containing separators, quotes or line breaks', () => {
    expect(toCsv([['x;y', 'say "hi"', 'two\nlines', 'plain']])).toBe('"x;y";"say ""hi""";"two\nlines";plain');
  });

  it('writes missing values as empty cells', () => {
    expect(toCsv([[null, undefined, 0]])).toBe(';;0');
  });
});

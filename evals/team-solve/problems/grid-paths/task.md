Count paths from the top-left cell (1,1) to the bottom-right cell (R,C) of a
grid, moving only right or down, through free cells ('.') and blocked cells
('#'). The count is modulo 1_000_000_007. Start and end cells are free.

## Input
First line: R C. Then R lines of C characters ('.' or '#').

## Output
One integer: number of paths mod 1e9+7.

## Examples
- 2x2 all free -> `2`

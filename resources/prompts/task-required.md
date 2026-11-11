`{{tool-name}}` needs a claimed task.

Work on this board starts by taking a task, so that two branches cannot
silently do the same job and so the run's record says who was doing what.

Look at the board and take something:

```tool-call
{"name": "task", "args": {"action": "list"}}
```

then claim it with `{"name": "task", "args": {"action": "claim", "id": "<id>"}}`.
If nothing on the board fits, create the task you are about to do — a one-line
title is enough — creating it claims it.

# Usability think-aloud protocol (Story 6.2.8, Task 6.2.8a; UX-96, UX-123, assumption A5)

Status: PROTOCOL WRITTEN. NO SESSION HAS BEEN RUN. Nothing in this file is a result; results go in
`usability-results.md` (template below), written only by the owner after real sessions. Until then every
usability claim in the plan is UNMEASURED.

The sessions are an owner wall-clock blocker (class OD-2): they need real people, a phone, and a build that contains the
rendered slice, the consent sheet, the constraint UI and the smart-dimension chip. This document is everything that
can be prepared without them.

## 1. Who and what you need

- n = 3 to 5 participants: the owner plus 2 to 4 people the owner already knows who make diagrams (Excalidraw, draw.io
  or FreeCAD users), invited unpaid. The names were to be recorded before W1 (D-48): ______ (owner fills in; not
  recorded in this repository at the time of writing).
- At least one participant on a phone with no stylus. A pen is optional; task 5's second half runs only if one is available.
- With fewer than 2 participants other than the owner the result is "owner-only, UNMEASURED against others". It is
  never PASS.
- Devices and builds: a desktop build and a phone build of the same commit; for the small-window session an Android
  window or emulator of 320 x 480 dp at font scale 2.0 (the smallest guaranteed window). Record the commit SHA and the
  device models in the results file.
- Prepare before each session: a graph with one classic sketch project the participant has not seen, a second project
  holding one technical sheet with ONE line drawn (task 6), the new-editor mode set to its default (not yet turned on, so
  task 2 starts from the real default), Finger drawing at its default, screen recording or note paper, a timer.
- Do not run the sessions against a debug overlay, a seeded hint state or a pre-opened panel: the first-run state is the
  thing under test.

## 2. Facilitator rules

1. Read the script aloud verbatim. Do not name a tool, a menu, a setting or a gesture. Do not say where anything is.
2. Say "please think aloud" once at the start and "what are you thinking?" only after 10 seconds of silence.
3. No help. A task counts as completed unaided only if the participant finishes it with no hint, no pointing and no
   "try the top bar". If you help, stop the task, mark it "helped", and record what the hint was.
4. A task is abandoned at 3 minutes (tasks 1 to 4 and 6) or 4 minutes (task 5, task 2 including the question). Mark it "gave up" with the time.
5. Count a wrong turn each time the participant opens a control or panel that is not on the route to the goal.
6. Write the participant's words down verbatim for every surprise, hesitation or complaint. Quotes beat paraphrase.
7. Do not defend the design. Do not ask "did you see the X?". Ask "what did you expect to happen?" after an unexpected result.
8. Between tasks reset the sketch and the app state to the preparation state above.

## 3. Participant script (read aloud)

Opening: "Thanks for helping. This is a test of the app, not of you. Anything that is hard is the app's fault and is exactly
what I want to find. Please say out loud what you are looking for and what you expect. I will not be able to help while
you work. You can stop at any time."

- Task 1, find the hidden tool. (Compact width: use the phone or a narrow window so the tool strip does not show every
  tool.) "Please draw a diamond."
- Task 2, turn on the new editor and read what it tells you. "This app has a new sketch editor that is switched off. Please
  turn it on and read anything it shows you. Tell me when you are done reading." Then hand over the written question and
  do not read it aloud (read-aloud changes comprehension): "If you upgrade this sketch and then open this project on another
  device that has not been updated, what happens when you open this project there? Which sketches does it affect?"
  Correct = the whole project, all its sketches, will not open. "Just this sketch" or "nothing" is wrong. Record the answer verbatim. At least one
  session runs this task at font scale 2.0 on the consent sheet (UX-123, U2).
- Task 3, add a Parallel constraint. "Please draw two lines and make them parallel."
- Task 4, box and arrow by touch (phone, touch only, no stylus, no keyboard). "Please draw a box and an arrow pointing at it."
- Task 5, pan with Finger drawing ON. "Finger drawing is switched on. A shape is off the edge of the screen. Please move the view so
  you can see it." Then, only if a pen is available: hold the pen to the screen, put it down, and ask "What do you think just
  happened?" Record whether they describe the finger-to-pen flip.
- Task 6, dimension (technical sheet with one line drawn). "This line should be exactly 6 inches long. Please make it so."
  First attempt unaided. If they could not do it, show the smart-dimension chip (Story 4.2.7), ask them to try again, and
  record which path they used and the number of actions it took. The unaided and the shown attempts are two separate rows.

Closing, ask each participant: "Which part felt slowest or most confusing?" and "Did anything pop up on its own that
interrupted you?" (the nag observation, section 5).

## 4. What to record per task (one row per participant per task)

| Field | Values |
|---|---|
| Completed unaided | yes / no (helped, gave up) |
| Time | mm:ss from the end of the script line to done or abandoned |
| Wrong turns | count, with the controls opened |
| Quotes | verbatim |
| Window and font scale | width dp, font scale (one session of task 4 or 6 at 320 x 480 dp, 2.0) |

Small-window session: one session (task 4 or 6) runs in 320 x 480 dp at font scale 2.0. Note whether the chrome left a usable drawing area (yes / no, with
a rough description of how much canvas remained).

Task 2 also records the comprehension answer (correct / wrong, verbatim). Task 6 records the path found and the action count.

## 5. Nag observation (iteration 14; Task 1.4.6i)

The "Upgrade this sketch?" sheet opens by itself at most once per sketch project per day. For every participant who
meets it (font scale 1.15 or more at 320 dp, or 1.52 or more at 360 dp, with the new-editor mode `On`) record whether it
read as a nag or interrupted work, with a quote. Two or more such reports make the daily cadence a defect against Task
1.4.6i; the owner then chooses a weekly cadence or a manual-only sheet and records the choice in the ADR index.

## 6. Pass bar

With n participants (3 to 5):

| n | Tasks 1, 3, 4, 5 and task-2 comprehension, unaided (ceil(0.8 n)) | Task 6 unaided (ceil(0.6 n)) |
|---|---|---|
| 3 | 3 | 2 |
| 4 | 4 | 3 |
| 5 | 4 | 3 |

PASS needs every row at or above its bar. Every unmet task becomes a defect against the owning story or a documented owner
decision. With fewer than 2 participants besides the owner the result is "owner-only, UNMEASURED against others".

Parked variant: when the constraint UI is parked (A6 DEFER, import/export first, v1 editor extended only), tasks 3 and 6 are dropped, because they need Stories 4.2.3
and 4.2.7b to 4.2.7d. The bar is computed over tasks 1, 2, 4 and 5 and the result is labelled "constraint tasks not run".

## 7. Decision rule (record every branch taken in `usability-results.md`)

- Task 5 below the bar: promote the one-finger-pan alternatives first (the edge strip is labelled "Pan" and always visible
  while Finger drawing is ON; the Hand tool is pinned in the compact dock) with no default change, and re-run the
  session once with new participants. With no new participant available (the owner-only case) there is no re-run: record "Task 5 below the bar, not re-run,
  UNMEASURED against others"; the promoted alternatives still ship; the default does not flip on an unmeasured miss unless the owner records it.
  If the re-run still misses, the Finger drawing default flips to OFF everywhere and the finger-only-phone hint offers the action "Turn on Finger drawing".
- Two or more participants report that a single-letter shortcut did something unexpected: `SingleKeyShortcuts` defaults
  to OFF on desktop as well and the hint chip of Story 3.6.1 appears there.
- Task 2 below the bar (UX-123): the defect goes to the `SketchConsentCopy` wording or placement (Story 1.4.6); re-run once.
  A second miss is an owner decision recorded in the ADR index (for example a typed confirmation or an always-shown modal).
- Tasks 1, 3 or 4 below the bar: the defect goes to Story 3.6.8, 4.2.3 or 3.1.2 respectively; re-run once. A second
  miss is an owner decision recorded in the ADR index, not an automatic scope change.
- Two or more nag reports: see section 5.

## 8. Owner checklist (the only part that cannot be done by an agent)

- [ ] Names of 2 to 4 participants recorded (or "owner-only" accepted): ______
- [ ] Session date and the build commit: ______
- [ ] Sessions run, results entered in `usability-results.md` the same day (quotes decay)
- [ ] Decision rule applied; ADR index updated for any owner decision
- [ ] A defect filed against the owning story for every unmet task

## 9. Result template

Copy the table below into `usability-results.md`, fill it from the sessions, and delete nothing. The committed
`usability-results.md` is a blank of this template marked NOT RUN.

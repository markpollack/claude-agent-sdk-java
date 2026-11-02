#!/usr/bin/env python3
"""Offline protocol peer: validates supported 2.1.316 hook fields and applies temp writes."""
import json
import os
import sys
from pathlib import Path

root = Path(__file__).resolve().parent
(root / 'pid').write_text(str(os.getpid()))
cases = [('PreToolUse', name) for name in
         ['allow', 'deny', 'ask', 'modify', 'empty', 'controls', 'failure', 'null', 'async', 'async-timeout', 'unknown']]
cases += [('PostToolUse', 'post'), ('UserPromptSubmit', 'prompt')]
index = 0
callbacks = {}
observed = []

def unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise AssertionError('Duplicate field: ' + key)
        result[key] = value
    return result

def send(value):
    print(json.dumps(value), flush=True)

def request():
    event, name = cases[index]
    data = {'hook_event_name': event, 'session_id': 'offline-hook',
            'transcript_path': str(root / 'transcript.jsonl'), 'cwd': str(root),
            'tool_name': name, 'tool_use_id': name, 'tool_input': {'value': 'original'},
            'tool_response': {'result': 'done'}, 'prompt': 'offline'}
    send({'type': 'control_request', 'request_id': name,
          'request': {'subtype': 'hook_callback',
                      'callback_id': 'unregistered' if name == 'unknown' else callbacks[event],
                      'tool_use_id': name, 'input': data}})

def validate(payload):
    # Supported subset of the pinned native XD/Qfe schema; reject misspelled fields
    # rather than imitating native unknown-field stripping (which hid the old deny).
    allowed = {'continue', 'suppressOutput', 'stopReason', 'decision', 'systemMessage',
               'reason', 'async', 'asyncTimeout', 'hookSpecificOutput'}
    assert set(payload) <= allowed, 'Unknown/flattened hook fields: ' + str(payload)
    for field in ['continue', 'suppressOutput']:
        if field in payload:
            assert type(payload[field]) is bool, field + ' must be boolean when present'
    for field in ['stopReason', 'systemMessage', 'reason']:
        if field in payload:
            assert isinstance(payload[field], str), field + ' must be string'
    if 'decision' in payload:
        assert payload['decision'] in ['approve', 'block']
    if 'async' in payload:
        assert payload['async'] is True
    if 'asyncTimeout' in payload:
        assert type(payload['asyncTimeout']) is int
    if 'hookSpecificOutput' in payload:
        specific = payload['hookSpecificOutput']
        assert specific['hookEventName'] in ['PreToolUse', 'PostToolUse', 'UserPromptSubmit']
        allowed_specific = {'hookEventName', 'additionalContext'}
        if specific['hookEventName'] == 'PreToolUse':
            allowed_specific |= {'permissionDecision', 'permissionDecisionReason', 'updatedInput'}
        assert set(specific) <= allowed_specific
        for field in ['permissionDecisionReason', 'additionalContext']:
            if field in specific:
                assert isinstance(specific[field], str)
        if 'permissionDecision' in specific:
            assert specific['permissionDecision'] in ['allow', 'deny', 'ask', 'defer']
        if 'updatedInput' in specific:
            assert isinstance(specific['updatedInput'], dict)

def apply(name, response):
    assert response['request_id'] == name
    if name == 'unknown':
        assert response['subtype'] == 'error' and isinstance(response['error'], str)
        return
    assert response['subtype'] == 'success'
    payload = response['response']
    validate(payload)
    specific = payload.get('hookSpecificOutput', {})
    if name in ['allow', 'deny', 'ask']:
        assert specific['hookEventName'] == 'PreToolUse'
        assert specific['permissionDecision'] == name
        assert 'continue' not in payload, 'Absent optional boolean was manufactured'
        if name == 'allow':
            (root / 'allowed-action').write_text('executed')
        elif name == 'deny':
            assert specific['permissionDecisionReason'] == 'policy denied'
            # The operation is deliberately not applied, as the protocol requests.
        else:
            assert not (root / 'asked-action').exists()
    elif name == 'modify':
        assert specific['hookEventName'] == 'PreToolUse'
        assert 'permissionDecision' not in specific
        (root / 'modified-action').write_text(specific['updatedInput']['value'])
    elif name == 'empty':
        assert payload == {}
    elif name == 'controls':
        assert payload == {'continue': False, 'suppressOutput': True, 'stopReason': 'stop',
                           'decision': 'block', 'systemMessage': 'notice', 'reason': 'policy'}
    elif name in ['failure', 'null']:
        assert payload['continue'] is False and payload['decision'] == 'block'
        assert 'Hook execution failed' in payload['reason']
    elif name in ['async', 'async-timeout']:
        assert payload['async'] is True
        if name == 'async-timeout':
            assert payload['asyncTimeout'] == 1234
        else:
            assert 'asyncTimeout' not in payload
    elif name in ['post', 'prompt']:
        assert specific == {'hookEventName': cases[index][0], 'additionalContext': name + ' context'}

try:
    for line in sys.stdin:
        with (root / 'wire.jsonl').open('a') as out:
            out.write(line)
        message = json.loads(line, object_pairs_hook=unique)
        if message['type'] in ['control_request', 'control']:
            # Async client's pre-existing legacy initialize envelope is accepted only
            # to reach its hook-response path; this is not native-init qualification.
            init = message['request'] if message['type'] == 'control_request' else message
            assert init['subtype'] == 'initialize'
            callbacks = {event: rows[0]['hookCallbackIds'][0]
                         for event, rows in init['hooks'].items()}
            send({'type': 'control_response', 'response': {'subtype': 'success',
                  'request_id': message['request_id'], 'response': {}}})
            request()
        elif message['type'] == 'control_response':
            apply(cases[index][1], message['response'])
            observed.append(cases[index][1])
            index += 1
            if index == len(cases):
                (root / 'outcome.json').write_text(json.dumps({'passed': True, 'cases': observed}))
                break
            request()
        else:
            raise AssertionError('No user/model submission is permitted')
except Exception as failure:
    (root / 'outcome.json').write_text(json.dumps({'passed': False, 'error': str(failure), 'cases': observed}))
    raise

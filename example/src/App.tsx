import { useCallback, useEffect, useRef, useState } from 'react';
import {
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
  type ScrollViewInstance,
} from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import Responsly from '@responsly/react-native-sdk';

const DEFAULT_SDK_KEY = 'rsk_demo';
const DEFAULT_API_HOST = 'https://api.responsly.com';

interface LogLine {
  id: number;
  time: string;
  text: string;
}

function now(): string {
  return new Date().toISOString().slice(11, 23);
}

function Button({ title, onPress }: { title: string; onPress: () => void }) {
  return (
    <Pressable
      accessibilityRole="button"
      onPress={onPress}
      style={({ pressed }) => [styles.button, pressed && styles.buttonPressed]}
    >
      <Text style={styles.buttonText}>{title}</Text>
    </Pressable>
  );
}

export default function App() {
  const [sdkKey, setSdkKey] = useState(DEFAULT_SDK_KEY);
  const [apiHost, setApiHost] = useState(DEFAULT_API_HOST);
  const [surveyId, setSurveyId] = useState('');
  const [force, setForce] = useState(false);
  const [paused, setPaused] = useState(false);
  const [lines, setLines] = useState<LogLine[]>([]);
  const nextId = useRef(0);
  const logView = useRef<ScrollViewInstance>(null);

  const log = useCallback((text: string) => {
    setLines((current) => [
      ...current,
      { id: nextId.current++, time: now(), text },
    ]);
  }, []);

  // Registered before `initialize` is called: listeners work either way.
  useEffect(
    () =>
      Responsly.addListener({
        onSurveyShown: (event) => log(`onSurveyShown ${JSON.stringify(event)}`),
        onSurveyNotShown: (event) =>
          log(`onSurveyNotShown ${JSON.stringify(event)}`),
        onQuestionAnswered: (event) =>
          log(`onQuestionAnswered ${JSON.stringify(event)}`),
        onSurveyCompleted: (event) =>
          log(`onSurveyCompleted ${JSON.stringify(event)}`),
        onSurveyDismissed: (event) =>
          log(`onSurveyDismissed ${JSON.stringify(event)}`),
        onSurveyError: (event) => log(`onSurveyError ${JSON.stringify(event)}`),
      }),
    [log]
  );

  const initialize = () => {
    const key = sdkKey.trim();
    const host = apiHost.trim();
    Responsly.initialize(key, {
      apiHost: host === '' ? undefined : host,
      logLevel: 'debug',
    });
    log(`initialize(${key}, ${host || DEFAULT_API_HOST})`);
  };

  const identify = () => {
    Responsly.identify('demo-user', {
      plan: 'pro',
      seats: 5,
      beta: true,
      signed_up: new Date(),
    });
    log('identify(demo-user)');
  };

  const track = () => {
    Responsly.track('purchase_completed', { value: 49.9 });
    log('track(purchase_completed)');
  };

  const screen = () => {
    Responsly.screen('Checkout');
    log('screen(Checkout)');
  };

  const showSurvey = () => {
    const id = surveyId.trim();
    Responsly.showSurvey(id, { force });
    log(`showSurvey(${id}, force: ${force})`);
  };

  const togglePause = () => {
    if (paused) {
      Responsly.resume();
      log('resume()');
    } else {
      Responsly.pause();
      log('pause()');
    }
    setPaused(!paused);
  };

  const close = () => {
    Responsly.closeSurvey();
    log('closeSurvey()');
  };

  const reset = () => {
    Responsly.reset();
    log('reset()');
  };

  return (
    <SafeAreaProvider>
      <SafeAreaView style={styles.screen}>
        <ScrollView
          style={styles.controls}
          contentContainerStyle={styles.controlsContent}
          keyboardShouldPersistTaps="handled"
        >
          <Text style={styles.label}>SDK key</Text>
          <TextInput
            style={styles.input}
            value={sdkKey}
            onChangeText={setSdkKey}
            autoCapitalize="none"
            autoCorrect={false}
            testID="sdk-key"
          />

          <Text style={styles.label}>API host</Text>
          <TextInput
            style={styles.input}
            value={apiHost}
            onChangeText={setApiHost}
            autoCapitalize="none"
            autoCorrect={false}
            keyboardType="url"
            testID="api-host"
          />

          <View style={styles.row}>
            <Button title="Initialize" onPress={initialize} />
            <Button title="Identify demo user" onPress={identify} />
          </View>
          <View style={styles.row}>
            <Button title="Track purchase_completed" onPress={track} />
            <Button title="Screen Checkout" onPress={screen} />
          </View>

          <Text style={styles.label}>Survey ID</Text>
          <TextInput
            style={styles.input}
            value={surveyId}
            onChangeText={setSurveyId}
            autoCapitalize="none"
            autoCorrect={false}
            placeholder="AbCd1234"
            testID="survey-id"
          />
          <View style={styles.row}>
            <Button title="Show survey" onPress={showSurvey} />
            <View style={styles.switchRow}>
              <Text style={styles.switchLabel}>Force</Text>
              <Switch value={force} onValueChange={setForce} testID="force" />
            </View>
          </View>

          <View style={styles.row}>
            <Button title={paused ? 'Resume' : 'Pause'} onPress={togglePause} />
            <Button title="Close" onPress={close} />
            <Button title="Reset" onPress={reset} />
          </View>
        </ScrollView>

        <View style={styles.logHeader}>
          <Text style={styles.label}>Log</Text>
          <Pressable accessibilityRole="button" onPress={() => setLines([])}>
            <Text style={styles.clear}>Clear</Text>
          </Pressable>
        </View>
        <ScrollView
          ref={logView}
          style={styles.log}
          contentContainerStyle={styles.logContent}
          onContentSizeChange={() =>
            logView.current?.scrollToEnd({ animated: true })
          }
          testID="log"
        >
          {lines.map((line) => (
            <Text key={line.id} style={styles.logLine} selectable>
              <Text style={styles.logTime}>{line.time} </Text>
              {line.text}
            </Text>
          ))}
        </ScrollView>
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: '#ffffff',
  },
  controls: {
    flexGrow: 0,
  },
  controlsContent: {
    padding: 16,
    gap: 8,
  },
  label: {
    fontSize: 13,
    fontWeight: '600',
    color: '#374151',
  },
  input: {
    borderWidth: 1,
    borderColor: '#d1d5db',
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 8,
    fontSize: 15,
    color: '#111827',
  },
  row: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    alignItems: 'center',
    gap: 8,
  },
  button: {
    backgroundColor: '#1f2937',
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 10,
  },
  buttonPressed: {
    opacity: 0.7,
  },
  buttonText: {
    color: '#ffffff',
    fontSize: 14,
    fontWeight: '600',
  },
  switchRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  switchLabel: {
    fontSize: 14,
    color: '#111827',
  },
  logHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: '#d1d5db',
  },
  clear: {
    fontSize: 13,
    color: '#2563eb',
  },
  log: {
    flex: 1,
    backgroundColor: '#f9fafb',
  },
  logContent: {
    padding: 16,
    gap: 4,
  },
  logLine: {
    fontFamily: Platform.select({ ios: 'Menlo', default: 'monospace' }),
    fontSize: 12,
    color: '#111827',
  },
  logTime: {
    color: '#6b7280',
  },
});

package logger

import (
	"bytes"
	"fmt"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/sirupsen/logrus"
)

const (
	red    = 31
	yellow = 33
	blue   = 36
	gray   = 37
)

const defaultTimestampFormat = "2006-01-02T15:04:05.000Z07:00"

const (
	CustomKeyFile = "@logger@file@"
	CustomKeyFunc = "@logger@func@"
)

var baseTimestamp time.Time

func init() {
	baseTimestamp = time.Now()
}

// CustomFormatter форматирует записи журнала в текст.
// Это форматтер logrus, настроенный для нужд Neo.
type CustomFormatter struct {
	// Показывать полную временную метку при выводе в терминал, а не время
	// с начала работы программы.
	FullTimestamp bool

	// Формат временной метки. Используется тот же формат, что и в time.Format
	// и time.Parse стандартной библиотеки.
	TimestampFormat string

	// Не сокращать название уровня журнала до четырех символов.
	DisableLevelTruncation bool

	// Дополнять название уровня пробелами до общей длины.
	// Этот параметр включает DisableLevelTruncation.
	PadLevelText bool

	// CallerPrettyfier позволяет настроить значения полей функции и файла,
	// когда включен ReportCaller. Пустое возвращенное значение удаляет
	// соответствующее поле.
	CallerPrettyfier func(*runtime.Frame) (function string, file string)

	terminalInitOnce sync.Once

	// Максимальная длина названия уровня, вычисляемая при инициализации.
	levelTextMaxLength int
}

func (f *CustomFormatter) init() {
	// Найти максимальную длину названия уровня.
	for _, level := range logrus.AllLevels {
		levelTextLength := utf8.RuneCount([]byte(level.String()))
		if levelTextLength > f.levelTextMaxLength {
			f.levelTextMaxLength = levelTextLength
		}
	}
}

// Format формирует одну запись журнала.
func (f *CustomFormatter) Format(entry *logrus.Entry) ([]byte, error) {
	data := make(logrus.Fields)
	for k, v := range entry.Data {
		data[k] = v
	}
	keys := make([]string, 0, len(data))
	for k := range data {
		if k == CustomKeyFile || k == CustomKeyFunc {
			continue
		}
		keys = append(keys, k)
	}

	sort.Strings(keys)

	var b *bytes.Buffer
	if entry.Buffer != nil {
		b = entry.Buffer
	} else {
		b = &bytes.Buffer{}
	}

	f.terminalInitOnce.Do(func() { f.init() })

	timestampFormat := f.TimestampFormat
	if timestampFormat == "" {
		timestampFormat = defaultTimestampFormat
	}
	f.printColored(b, entry, keys, data, timestampFormat)

	b.WriteByte('\n')
	return b.Bytes(), nil
}

func (f *CustomFormatter) printColored(b *bytes.Buffer, entry *logrus.Entry, keys []string, data logrus.Fields, timestampFormat string) {
	var levelColor int
	switch entry.Level {
	case logrus.DebugLevel, logrus.TraceLevel:
		levelColor = gray
	case logrus.WarnLevel:
		levelColor = yellow
	case logrus.ErrorLevel, logrus.FatalLevel, logrus.PanicLevel:
		levelColor = red
	case logrus.InfoLevel:
		levelColor = blue
	default:
		levelColor = blue
	}

	levelText := strings.ToUpper(entry.Level.String())
	if !f.DisableLevelTruncation && !f.PadLevelText {
		levelText = levelText[0:4]
	}
	if f.PadLevelText {
		// Сформировать шаблон выравнивания, например "%-6s" или "%-7s",
		// на основе максимальной длины названия уровня.
		formatString := "%-" + strconv.Itoa(f.levelTextMaxLength) + "s"
		// Дополнить название уровня пробелами, например:
		// 	- "INFO   "
		//	- "WARNING"
		levelText = fmt.Sprintf(formatString, levelText)
	}

	// Удалить завершающий перевод строки, чтобы поведение совпадало
	// с text_formatter из logrus и стандартным пакетом log.
	entry.Message = strings.TrimSuffix(entry.Message, "\n")

	caller := ""
	if entry.HasCaller() {
		var (
			funcVal string
			fileVal string
			ok      bool
		)
		if fileVal, ok = data[CustomKeyFile].(string); ok && fileVal != "" {
			delete(data, CustomKeyFile)
		}
		if funcVal, ok = data[CustomKeyFunc].(string); ok && funcVal != "" {
			delete(data, CustomKeyFunc)
		}
		if fileVal == "" && funcVal == "" {
			if f.CallerPrettyfier != nil {
				funcVal, fileVal = f.CallerPrettyfier(entry.Caller)
			} else {
				fileVal = fmt.Sprintf("%s:%d", entry.Caller.File, entry.Caller.Line)
				funcVal = fmt.Sprintf("%s()", entry.Caller.Function)
			}
		}

		b := strings.Builder{}
		if fileVal != "" {
			b.WriteByte(' ')
			b.WriteString(fileVal)
		}
		if funcVal != "" {
			b.WriteByte(' ')
			b.WriteString(funcVal)
		}
		caller = b.String()
	}

	switch {
	case !f.FullTimestamp:
		_, _ = fmt.Fprintf(b, "\x1b[%dm%s\x1b[0m[%04d]%s %-44s ", levelColor, levelText, int(entry.Time.Sub(baseTimestamp)/time.Second), caller, entry.Message)
	default:
		_, _ = fmt.Fprintf(b, "\x1b[%dm%s\x1b[0m[%s]%s %-44s ", levelColor, levelText, entry.Time.Format(timestampFormat), caller, entry.Message)
	}
	for _, k := range keys {
		v := data[k]
		_, _ = fmt.Fprintf(b, " \x1b[%dm%s\x1b[0m=", levelColor, k)
		f.appendValue(b, v)
	}
}

func (f *CustomFormatter) needsQuoting(text string) bool {
	for _, ch := range text {
		if !((ch >= 'a' && ch <= 'z') ||
			(ch >= 'A' && ch <= 'Z') ||
			(ch >= '0' && ch <= '9') ||
			ch == '-' || ch == '.' || ch == '_' || ch == '/' || ch == '@' || ch == '^' || ch == '+') {
			return true
		}
	}
	return false
}

func (f *CustomFormatter) appendValue(b *bytes.Buffer, value interface{}) {
	stringVal, ok := value.(string)
	if !ok {
		stringVal = fmt.Sprint(value)
	}

	if !f.needsQuoting(stringVal) {
		b.WriteString(stringVal)
	} else {
		b.WriteString(fmt.Sprintf("%q", stringVal))
	}
}

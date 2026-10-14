package client

import (
	"context"
	"errors"
	"io"

	"github.com/sirupsen/logrus"
)

func checkStreamError(tp string, err error, streamErr error) bool {
	if errors.Is(err, io.EOF) {
		logrus.Errorf("Поток %s закрыт", tp)
		return false
	}
	if errors.Is(streamErr, context.Canceled) {
		logrus.Debugf("Контекст потока %s отменен", tp)
		return false
	}
	if err != nil {
		logrus.Errorf("Ошибка чтения из потока %s: %v", tp, err)
		return false
	}
	return true
}

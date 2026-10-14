package cli

import (
	"bytes"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/spf13/cobra"
	"github.com/stretchr/testify/require"

	"github.com/c4t-but-s4d/neo/v2/internal/client"
)

func TestValidateEntryHandlesShortFiles(t *testing.T) {
	for _, content := range []string{"", "#", "#!"} {
		t.Run(content, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "short-script")
			require.NoError(t, os.WriteFile(path, []byte(content), 0600))
			require.NotPanics(t, func() {
				require.NotEmpty(t, (&addCLI{}).validateEntry(path))
			})
		})
	}
}

func TestConfirmNewVersionWithYesDoesNotPromptOrReadInput(t *testing.T) {
	var output bytes.Buffer
	confirmed, err := confirmNewVersion(strings.NewReader(""), &output, true)
	require.NoError(t, err)
	require.True(t, confirmed)
	require.Empty(t, output.String())
}

func TestConfirmNewVersionKeepsInteractivePrompt(t *testing.T) {
	tests := []struct {
		name      string
		input     string
		confirmed bool
	}{
		{name: "английское да", input: "y\n", confirmed: true},
		{name: "английское yes", input: "YES\n", confirmed: true},
		{name: "русская буква", input: "д\n", confirmed: true},
		{name: "русское да", input: "Да\n", confirmed: true},
		{name: "отказ", input: "нет\n", confirmed: false},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			var output bytes.Buffer
			confirmed, err := confirmNewVersion(strings.NewReader(test.input), &output, false)
			require.NoError(t, err)
			require.Equal(t, test.confirmed, confirmed)
			require.Contains(t, output.String(), "Добавить новую версию? да/нет")
		})
	}
}

func TestConfirmNewVersionReturnsEOFWithoutAssumingYes(t *testing.T) {
	var output bytes.Buffer
	confirmed, err := confirmNewVersion(strings.NewReader(""), &output, false)
	require.ErrorIs(t, err, io.EOF)
	require.False(t, confirmed)
	require.Contains(t, output.String(), "Добавить новую версию? да/нет")
}

func TestNewAddReadsYesFlag(t *testing.T) {
	command := &cobra.Command{}
	command.Flags().String("id", "", "")
	command.Flags().Bool("dir", false, "")
	command.Flags().Duration("interval", 0, "")
	command.Flags().Duration("timeout", 0, "")
	command.Flags().Bool("endless", false, "")
	command.Flags().Bool("disabled", false, "")
	command.Flags().Bool("yes", true, "")

	result := NewAdd(command, []string{"job.py"}, &client.Config{})
	add, ok := result.(*addCLI)
	require.True(t, ok)
	require.True(t, add.yes)
}

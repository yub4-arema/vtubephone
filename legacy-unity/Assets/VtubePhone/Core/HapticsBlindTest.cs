namespace VtubePhone.Core
{
    public sealed class HapticsBlindTest
    {
        private bool? _expectedYes;
        public bool AwaitingAnswer => _expectedYes.HasValue;
        public int Correct { get; private set; }
        public int Total { get; private set; }
        public double Accuracy => Total == 0 ? 0 : (double)Correct / Total;
        public bool Begin(bool expectedYes)
        {
            if (AwaitingAnswer) return false;
            _expectedYes = expectedYes; return true;
        }
        public bool Answer(bool yes)
        {
            if (!AwaitingAnswer) return false;
            if (yes == _expectedYes.Value) Correct++;
            Total++; Cancel(); return true;
        }
        public void Cancel() => _expectedYes = null;
    }
}
